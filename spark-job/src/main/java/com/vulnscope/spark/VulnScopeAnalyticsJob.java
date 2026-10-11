package com.vulnscope.spark;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.apache.spark.sql.functions.*;

public final class VulnScopeAnalyticsJob {

    private VulnScopeAnalyticsJob() {
    }

    public static void main(String[] args) {
        Map<String, String> options = parseArgs(args);
        String input = options.getOrDefault("input", "hdfs:///vulnscope/input/vulnerabilities.jsonl");
        String output = options.getOrDefault("output", "hdfs:///vulnscope/output");
        String dimension = options.getOrDefault("dimension", "ALL").toUpperCase();

        SparkSession spark = SparkSession.builder()
                .appName("VulnScope Six-Dimension Analytics")
                .getOrCreate();
        spark.sparkContext().setLogLevel("WARN");

        try {
            Dataset<Row> source = prepare(spark.read().json(input)).cache();
            long sourceRows = source.count();
            System.out.println("VULNSCOPE_SOURCE_ROWS=" + sourceRows);
            if (sourceRows == 0) {
                throw new IllegalStateException("No source rows found at " + input);
            }

            if (hasJdbcOutput(options)) writeCatalog(source, options);
            if (matches(dimension, "D1")) write(dimensionTrend(source), "D1", "rpt_vuln_trend", output, options);
            if (matches(dimension, "D2")) write(dimensionSeverity(source), "D2", "rpt_severity_distribution", output, options);
            if (matches(dimension, "D3")) write(dimensionCwe(source), "D3", "rpt_cwe_top", output, options);
            if (matches(dimension, "D4")) write(dimensionMaven(source), "D4", "rpt_maven_package_risk", output, options);
            if (matches(dimension, "D5")) write(dimensionScatter(source), "D5", "rpt_risk_scatter", output, options);
            if (matches(dimension, "D6")) write(dimensionFlow(source), "D6", "rpt_risk_flow", output, options);
        } finally {
            spark.stop();
        }
    }

    private static Dataset<Row> prepare(Dataset<Row> source) {
        Column score = when(col("cvss_score").isNotNull(), col("cvss_score"))
                .when(upper(col("severity")).equalTo("CRITICAL"), lit(9.5))
                .when(upper(col("severity")).equalTo("HIGH"), lit(8.0))
                .when(upper(col("severity")).equalTo("MEDIUM"), lit(5.5))
                .when(upper(col("severity")).equalTo("LOW"), lit(2.5))
                .otherwise(lit(0.0));
        return source
                .withColumn("severity", upper(coalesce(col("severity"), lit("UNKNOWN"))))
                .withColumn("effective_score", score.cast("double"))
                .withColumn("published_time", to_timestamp(col("published_at")))
                .withColumn("stat_time", current_timestamp());
    }

    private static Dataset<Row> dimensionTrend(Dataset<Row> source) {
        Dataset<Row> base = source.dropDuplicates("vuln_id")
                .filter(col("published_time").isNotNull())
                .withColumn("stat_month", trunc(col("published_time"), "month"));
        Dataset<Row> grouped = base.groupBy("stat_month").agg(
                count(lit(1)).alias("total_count"),
                sum(when(col("severity").isin("CRITICAL", "HIGH"), 1).otherwise(0)).alias("high_count")
        );
        WindowSpec cumulative = Window.orderBy(col("stat_month")).rowsBetween(Window.unboundedPreceding(), Window.currentRow());
        return grouped.withColumn("cumulative_count", sum(col("total_count")).over(cumulative))
                .withColumn("stat_time", current_timestamp())
                .orderBy("stat_month");
    }

    private static Dataset<Row> dimensionSeverity(Dataset<Row> source) {
        Dataset<Row> base = source.dropDuplicates("vuln_id");
        long total = base.count();
        return base.groupBy("severity")
                .agg(count(lit(1)).alias("vuln_count"))
                .withColumn("vuln_ratio", round(col("vuln_count").divide(lit(total)), 6))
                .withColumn("stat_time", current_timestamp())
                .orderBy(col("vuln_count").desc());
    }

    private static Dataset<Row> dimensionCwe(Dataset<Row> source) {
        Dataset<Row> grouped = source.dropDuplicates("vuln_id")
                .filter(col("cwe_id").isNotNull())
                .groupBy("cwe_id", "cwe_name")
                .agg(count(lit(1)).alias("vuln_count"), round(avg("effective_score"), 2).alias("avg_cvss"))
                .orderBy(col("vuln_count").desc(), col("avg_cvss").desc())
                .limit(10);
        return grouped.withColumn("rank_no", row_number().over(Window.orderBy(col("vuln_count").desc(), col("avg_cvss").desc())))
                .select("rank_no", "cwe_id", "cwe_name", "vuln_count", "avg_cvss")
                .withColumn("stat_time", current_timestamp());
    }

    private static Dataset<Row> dimensionMaven(Dataset<Row> source) {
        Dataset<Row> grouped = source.dropDuplicates("vuln_id", "package_name")
                .groupBy("group_id", "artifact_id", "package_name")
                .agg(
                        countDistinct("vuln_id").alias("vuln_count"),
                        sum(when(col("severity").equalTo("CRITICAL"), 1).otherwise(0)).alias("critical_count"),
                        round(avg("effective_score"), 2).alias("avg_cvss")
                )
                .withColumn("risk_score", round(col("vuln_count").multiply(col("avg_cvss"))
                        .plus(col("critical_count").multiply(5)), 2))
                .orderBy(col("risk_score").desc())
                .limit(30);
        return grouped.withColumn("rank_no", row_number().over(Window.orderBy(col("risk_score").desc())))
                .withColumn("stat_time", current_timestamp());
    }

    private static Dataset<Row> dimensionScatter(Dataset<Row> source) {
        return source.dropDuplicates("vuln_id", "package_name")
                .filter(col("effective_score").gt(0))
                .select(
                        col("vuln_id"), col("package_name"),
                        round(col("effective_score"), 1).alias("cvss_score"),
                        coalesce(col("affected_version_count"), lit(0)).cast("int").alias("affected_version_count"),
                        coalesce(col("reference_count"), lit(0)).cast("int").alias("reference_count"),
                        col("severity"), current_timestamp().alias("stat_time")
                )
                .orderBy(col("cvss_score").desc(), col("affected_version_count").desc())
                .limit(300);
    }

    private static Dataset<Row> dimensionFlow(Dataset<Row> source) {
        Dataset<Row> base = source.dropDuplicates("vuln_id", "package_name")
                .filter(col("group_id").isNotNull().and(col("cwe_id").isNotNull()));
        Dataset<Row> first = base.groupBy(col("group_id").alias("source_node"), col("cwe_id").alias("target_node"))
                .agg(count(lit(1)).alias("flow_value"))
                .withColumn("source_level", lit(1)).withColumn("target_level", lit(2))
                .orderBy(col("flow_value").desc()).limit(40);
        Dataset<Row> second = base.groupBy(col("cwe_id").alias("source_node"), col("severity").alias("target_node"))
                .agg(count(lit(1)).alias("flow_value"))
                .withColumn("source_level", lit(2)).withColumn("target_level", lit(3))
                .orderBy(col("flow_value").desc()).limit(40);
        return first.unionByName(second).withColumn("stat_time", current_timestamp());
    }

    private static void write(Dataset<Row> result, String code, String table, String output, Map<String, String> options) {
        long rows = result.count();
        System.out.println("VULNSCOPE_" + code + "_ROWS=" + rows);
        result.coalesce(1).write().mode(SaveMode.Overwrite).json(output + "/" + code.toLowerCase());

        if (hasJdbcOutput(options)) writeToMysql(result, table, options);
    }

    private static void writeCatalog(Dataset<Row> source, Map<String, String> options) {
        Dataset<Row> vulnerabilities = source.selectExpr("vuln_id", "source_name", "summary", "to_timestamp(published_at) AS published_at", "to_timestamp(modified_at) AS modified_at", "severity", "CAST(effective_score AS DECIMAL(4,1)) AS cvss_score", "cwe_id", "cwe_name", "CAST(known_exploited AS INT) AS known_exploited", "CAST(fixed_available AS INT) AS fixed_available", "CAST(reference_count AS INT) AS reference_count").dropDuplicates("vuln_id");
        Dataset<Row> packageVulnerabilities = source.selectExpr("vuln_id", "ecosystem", "group_id", "artifact_id", "package_name", "CAST(affected_version_count AS INT) AS affected_versions", "fixed_version").dropDuplicates("vuln_id", "ecosystem", "package_name");
        System.out.println("VULNSCOPE_CATALOG_VULNERABILITIES=" + vulnerabilities.count());
        System.out.println("VULNSCOPE_CATALOG_PACKAGES=" + packageVulnerabilities.count());
        writeToMysql(vulnerabilities, "vulnerability", options);
        writeToMysql(packageVulnerabilities, "package_vulnerability", options);
    }

    private static boolean hasJdbcOutput(Map<String, String> options) {
        String jdbcUrl = options.get("jdbc-url");
        return jdbcUrl != null && !jdbcUrl.trim().isEmpty();
    }

    private static void writeToMysql(Dataset<Row> result, String table, Map<String, String> options) {
        clearTable(table, options);
        result.write().mode(SaveMode.Append).jdbc(options.get("jdbc-url"), table, jdbcProperties(options));
    }

    private static void clearTable(String table, Map<String, String> options) {
        try (Connection connection = DriverManager.getConnection(options.get("jdbc-url"), jdbcProperties(options));
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + table);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to clear MySQL table " + table, exception);
        }
    }

    private static Properties jdbcProperties(Map<String, String> options) {
        String password = System.getenv("VULNSCOPE_DB_PASSWORD");
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("VULNSCOPE_DB_PASSWORD is required for JDBC output");
        }
        Properties properties = new Properties();
        properties.setProperty("user", require(options, "jdbc-user"));
        properties.setProperty("password", password);
        properties.setProperty("driver", "com.mysql.cj.jdbc.Driver");
        return properties;
    }

    private static boolean matches(String requested, String code) {
        return "ALL".equals(requested) || code.equals(requested);
    }

    private static String require(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing --" + key);
        }
        return value;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            if (!args[index].startsWith("--") || index + 1 >= args.length) {
                throw new IllegalArgumentException("Arguments must be --key value pairs");
            }
            options.put(args[index].substring(2), args[index + 1]);
        }
        return options;
    }
}
