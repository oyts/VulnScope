package com.vulnscope.admin;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SparkTaskService {
    private static final Pattern APPLICATION_ID = Pattern.compile("application_\\d+_\\d+");
    private static final Pattern SOURCE_ROWS = Pattern.compile("VULNSCOPE_SOURCE_ROWS=(\\d+)");
    private static final Pattern DIMENSION_ROWS = Pattern.compile("VULNSCOPE_D([1-6])_ROWS=(\\d+)");
    private static final Set<String> DIMENSIONS = Set.of("ALL", "D1", "D2", "D3", "D4", "D5", "D6");

    private final JdbcTemplate jdbc;
    private final Executor executor;
    private final boolean enabled;
    private final String sshHost;
    private final int sshPort;
    private final String sshUsername;
    private final String sshPassword;
    private final String sparkSubmitPath;
    private final String hdfsCommandPath;
    private final String localJarPath;
    private final String remoteJarPath;
    private final String inputPath;
    private final String outputRoot;
    private final String jdbcUrl;
    private final String jdbcUser;
    private final String jdbcPassword;
    private final int timeoutMinutes;

    public SparkTaskService(JdbcTemplate jdbc,
                            @Qualifier("sparkTaskExecutor") Executor executor,
                            @Value("${app.spark.enabled:false}") boolean enabled,
                            @Value("${app.spark.ssh-host}") String sshHost,
                            @Value("${app.spark.ssh-port:22}") int sshPort,
                            @Value("${app.spark.ssh-username}") String sshUsername,
                            @Value("${app.spark.ssh-password:}") String sshPassword,
                            @Value("${app.spark.spark-submit-path}") String sparkSubmitPath,
                            @Value("${app.spark.hdfs-command-path}") String hdfsCommandPath,
                            @Value("${app.spark.local-jar-path}") String localJarPath,
                            @Value("${app.spark.remote-jar-path}") String remoteJarPath,
                            @Value("${app.spark.input-path}") String inputPath,
                            @Value("${app.spark.output-root}") String outputRoot,
                            @Value("${app.spark.jdbc-url}") String jdbcUrl,
                            @Value("${app.spark.jdbc-user}") String jdbcUser,
                            @Value("${app.spark.jdbc-password:}") String jdbcPassword,
                            @Value("${app.spark.timeout-minutes:20}") int timeoutMinutes) {
        this.jdbc = jdbc;
        this.executor = executor;
        this.enabled = enabled;
        this.sshHost = sshHost;
        this.sshPort = sshPort;
        this.sshUsername = sshUsername;
        this.sshPassword = sshPassword;
        this.sparkSubmitPath = sparkSubmitPath;
        this.hdfsCommandPath = hdfsCommandPath;
        this.localJarPath = localJarPath;
        this.remoteJarPath = remoteJarPath;
        this.inputPath = inputPath;
        this.outputRoot = outputRoot.replaceAll("/+$", "");
        this.jdbcUrl = jdbcUrl;
        this.jdbcUser = jdbcUser;
        this.jdbcPassword = jdbcPassword;
        this.timeoutMinutes = timeoutMinutes;
    }

    public Map<String, Object> submit(long userId, String requestedDimension) {
        if (!enabled) throw new IllegalStateException("Spark任务提交功能未启用");
        if (sshPassword.isBlank()) throw new IllegalStateException("未配置node1 SSH密码");
        if (jdbcPassword.isBlank()) throw new IllegalStateException("未配置Spark JDBC密码");
        String dimension = requestedDimension == null ? "ALL" : requestedDimension.trim().toUpperCase();
        if (!DIMENSIONS.contains(dimension)) throw new IllegalArgumentException("分析维度只能为ALL或D1至D6");
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM analysis_task WHERE status IN ('PENDING','RUNNING')", Integer.class);
        if (active != null && active > 0) throw new IllegalStateException("已有Spark任务正在运行，请等待完成");

        String suffix = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        String taskNo = "TASK-UI-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + suffix;
        String outputPath = outputRoot + "/" + taskNo.toLowerCase();
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO analysis_task(task_no,dimension_code,status,stage,progress,hdfs_input_path,hdfs_output_path,submitted_by) VALUES(?,?,'PENDING','QUEUED',0,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, taskNo);
            statement.setString(2, dimension);
            statement.setString(3, inputPath);
            statement.setString(4, outputPath);
            statement.setLong(5, userId);
            return statement;
        }, holder);
        long taskId = holder.getKey().longValue();
        executor.execute(() -> runTask(taskId, taskNo, dimension, outputPath));
        return task(taskId);
    }

    public Map<String, Object> task(long taskId) {
        return jdbc.queryForMap("SELECT * FROM analysis_task WHERE id=?", taskId);
    }

    private void runTask(long taskId, String taskNo, String dimension, String outputPath) {
        Session session = null;
        StringBuilder log = new StringBuilder();
        try {
            update(taskId, "RUNNING", "UPLOADING_JAR", 8, null, null);
            Path jar = Path.of(localJarPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(jar)) throw new IllegalStateException("Spark JAR不存在，请先构建：" + jar);

            session = openSession();
            String remoteDirectory = remoteJarPath.substring(0, remoteJarPath.lastIndexOf('/'));
            exec(session, "mkdir -p " + shellQuote(remoteDirectory), Duration.ofSeconds(20));
            uploadJar(session, jar);

            update(taskId, "RUNNING", "HDFS_CHECK", 18, null, null);
            exec(session, shellQuote(hdfsCommandPath) + " dfs -test -e " + shellQuote(inputPath), Duration.ofSeconds(30));

            update(taskId, "RUNNING", "YARN_SUBMITTED", 30, null, null);
            int exitStatus = executeSpark(session, taskId, taskNo, dimension, outputPath, log);
            if (exitStatus != 0) throw new IllegalStateException("spark-submit退出码为" + exitStatus);

            String applicationId = find(APPLICATION_ID, log.toString());
            if (applicationId == null) throw new IllegalStateException("作业结束但未捕获YARN Application ID");
            long resultRows = verifyMysqlResults(dimension);
            update(taskId, "SUCCEEDED", "SUCCEEDED", 100, null, tail(log));
            jdbc.update("UPDATE analysis_task SET application_id=?,result_rows=?,finished_at=NOW() WHERE id=?",
                    applicationId, resultRows, taskId);
        } catch (Exception exception) {
            String message = safeMessage(exception);
            update(taskId, "FAILED", "FAILED", 100, message, tail(log));
            jdbc.update("UPDATE analysis_task SET finished_at=NOW() WHERE id=?", taskId);
        } finally {
            if (session != null && session.isConnected()) session.disconnect();
        }
    }

    private Session openSession() throws Exception {
        Session session = new JSch().getSession(sshUsername, sshHost, sshPort);
        session.setPassword(sshPassword);
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(12_000);
        return session;
    }

    private void uploadJar(Session session, Path localJar) throws Exception {
        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        try {
            channel.connect(10_000);
            channel.put(localJar.toString(), remoteJarPath);
        } finally {
            channel.disconnect();
        }
    }

    private int executeSpark(Session session, long taskId, String taskNo, String dimension,
                             String outputPath, StringBuilder log) throws Exception {
        String command = "export VULNSCOPE_DB_PASSWORD=" + shellQuote(jdbcPassword) + "; exec "
                + shellQuote(sparkSubmitPath)
                + " --master yarn --deploy-mode client --name " + shellQuote("VulnScope " + taskNo)
                + " --num-executors 3 --executor-cores 1 --executor-memory 512m --driver-memory 512m"
                + " --conf spark.yarn.maxAppAttempts=1"
                + " --class com.vulnscope.spark.VulnScopeAnalyticsJob " + shellQuote(remoteJarPath)
                + " --input " + shellQuote(inputPath)
                + " --output " + shellQuote(outputPath)
                + " --dimension " + shellQuote(dimension)
                + " --jdbc-url " + shellQuote(jdbcUrl)
                + " --jdbc-user " + shellQuote(jdbcUser)
                + " 2>&1";
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command);
        long deadline = System.nanoTime() + Duration.ofMinutes(timeoutMinutes).toNanos();
        Map<String, Long> dimensionRows = new LinkedHashMap<>();
        String capturedApplicationId = null;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(channel.getInputStream(), StandardCharsets.UTF_8));
            channel.connect(10_000);
            String line;
            while (true) {
                while (reader.ready() && (line = reader.readLine()) != null) {
                    append(log, line);
                    Matcher appMatcher = APPLICATION_ID.matcher(line);
                    if (capturedApplicationId == null && appMatcher.find()) {
                        capturedApplicationId = appMatcher.group();
                        jdbc.update("UPDATE analysis_task SET application_id=?,stage='SPARK_RUNNING',progress=42 WHERE id=?",
                                capturedApplicationId, taskId);
                    }
                    Matcher sourceMatcher = SOURCE_ROWS.matcher(line);
                    if (sourceMatcher.find()) {
                        jdbc.update("UPDATE analysis_task SET source_rows=?,stage='SPARK_RUNNING',progress=48 WHERE id=?",
                                Long.parseLong(sourceMatcher.group(1)), taskId);
                    }
                    Matcher dimensionMatcher = DIMENSION_ROWS.matcher(line);
                    if (dimensionMatcher.find()) {
                        dimensionRows.put("D" + dimensionMatcher.group(1), Long.parseLong(dimensionMatcher.group(2)));
                        int progress = Math.min(90, 50 + dimensionRows.size() * 7);
                        long rows = dimensionRows.values().stream().mapToLong(Long::longValue).sum();
                        jdbc.update("UPDATE analysis_task SET stage=?,progress=?,result_rows=? WHERE id=?",
                                "DIMENSION_D" + dimensionMatcher.group(1), progress, rows, taskId);
                    }
                }
                if (channel.isClosed()) break;
                if (System.nanoTime() > deadline) {
                    channel.disconnect();
                    throw new IllegalStateException("Spark任务超过" + timeoutMinutes + "分钟，已停止等待");
                }
                Thread.sleep(150);
            }
            while ((line = reader.readLine()) != null) append(log, line);
            return channel.getExitStatus();
        } finally {
            channel.disconnect();
        }
    }

    private String exec(Session session, String command, Duration timeout) throws Exception {
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command + " 2>&1");
        StringBuilder output = new StringBuilder();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(channel.getInputStream(), StandardCharsets.UTF_8));
            channel.connect(10_000);
            String line;
            while (true) {
                while (reader.ready() && (line = reader.readLine()) != null) append(output, line);
                if (channel.isClosed()) break;
                if (System.nanoTime() > deadline) throw new IllegalStateException("远程命令执行超时");
                Thread.sleep(100);
            }
            while ((line = reader.readLine()) != null) append(output, line);
            if (channel.getExitStatus() != 0) throw new IllegalStateException("远程环境检查失败：" + tail(output));
            return output.toString();
        } finally {
            channel.disconnect();
        }
    }

    private long verifyMysqlResults(String dimension) {
        Map<String, String> tables = Map.of(
                "D1", "rpt_vuln_trend", "D2", "rpt_severity_distribution", "D3", "rpt_cwe_top",
                "D4", "rpt_maven_package_risk", "D5", "rpt_risk_scatter", "D6", "rpt_risk_flow");
        long total = 0;
        for (Map.Entry<String, String> entry : tables.entrySet()) {
            if (!"ALL".equals(dimension) && !entry.getKey().equals(dimension)) continue;
            Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM " + entry.getValue(), Long.class);
            if (rows == null || rows == 0) throw new IllegalStateException(entry.getKey() + "结果表为空，JDBC回写验收失败");
            total += rows;
        }
        return total;
    }

    private void update(long taskId, String status, String stage, int progress, String error, String log) {
        jdbc.update("UPDATE analysis_task SET status=?,stage=?,progress=?,error_message=?,log_excerpt=?,started_at=COALESCE(started_at,NOW()) WHERE id=?",
                status, stage, progress, error, log, taskId);
    }

    private static void append(StringBuilder output, String line) {
        output.append(line).append('\n');
        if (output.length() > 24_000) output.delete(0, output.length() - 24_000);
    }

    private static String tail(StringBuilder output) {
        String value = output.toString();
        return value.length() <= 8_000 ? value : value.substring(value.length() - 8_000);
    }

    private static String safeMessage(Exception exception) {
        String value = exception.getMessage();
        if (value == null || value.isBlank()) value = exception.getClass().getSimpleName();
        return value.length() <= 1_000 ? value : value.substring(0, 1_000);
    }

    private static String find(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group() : null;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\\"'\\\"'") + "'";
    }
}
