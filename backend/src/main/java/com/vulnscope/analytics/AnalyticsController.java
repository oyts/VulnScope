package com.vulnscope.analytics;

import com.vulnscope.common.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {
    private final JdbcTemplate jdbc;

    public AnalyticsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("vulnerabilities", scalar("SELECT COUNT(*) FROM vulnerability"));
        result.put("mavenPackages", scalar("SELECT COUNT(DISTINCT package_name) FROM package_vulnerability"));
        result.put("knownExploited", scalar("SELECT COUNT(*) FROM vulnerability WHERE known_exploited=1"));
        result.put("successfulTasks", scalar("SELECT COUNT(*) FROM analysis_task WHERE status='SUCCEEDED'"));
        result.put("updatedAt", jdbc.queryForObject("SELECT MAX(stat_time) FROM rpt_severity_distribution", Object.class));
        return ApiResponse.ok(result);
    }

    @GetMapping("/all")
    public ApiResponse<Map<String, Object>> all() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trend", jdbc.queryForList("SELECT * FROM rpt_vuln_trend ORDER BY stat_month"));
        result.put("severity", jdbc.queryForList("SELECT * FROM rpt_severity_distribution ORDER BY vuln_count DESC"));
        result.put("cweTop", jdbc.queryForList("SELECT * FROM rpt_cwe_top ORDER BY rank_no"));
        result.put("mavenRisk", jdbc.queryForList("SELECT * FROM rpt_maven_package_risk ORDER BY rank_no"));
        result.put("riskScatter", jdbc.queryForList("SELECT * FROM rpt_risk_scatter ORDER BY cvss_score DESC, affected_version_count DESC LIMIT 300"));
        result.put("riskFlow", jdbc.queryForList("SELECT * FROM rpt_risk_flow ORDER BY source_level, flow_value DESC"));
        return ApiResponse.ok(result);
    }

    @GetMapping("/trend") public ApiResponse<List<Map<String,Object>>> trend() { return ApiResponse.ok(jdbc.queryForList("SELECT * FROM rpt_vuln_trend ORDER BY stat_month")); }
    @GetMapping("/severity") public ApiResponse<List<Map<String,Object>>> severity() { return ApiResponse.ok(jdbc.queryForList("SELECT * FROM rpt_severity_distribution ORDER BY vuln_count DESC")); }
    @GetMapping("/cwe-top") public ApiResponse<List<Map<String,Object>>> cwe() { return ApiResponse.ok(jdbc.queryForList("SELECT * FROM rpt_cwe_top ORDER BY rank_no")); }
    @GetMapping("/maven-risk") public ApiResponse<List<Map<String,Object>>> maven() { return ApiResponse.ok(jdbc.queryForList("SELECT * FROM rpt_maven_package_risk ORDER BY rank_no")); }
    @GetMapping("/risk-scatter") public ApiResponse<List<Map<String,Object>>> scatter() { return ApiResponse.ok(jdbc.queryForList("SELECT * FROM rpt_risk_scatter ORDER BY cvss_score DESC LIMIT 300")); }
    @GetMapping("/risk-flow") public ApiResponse<List<Map<String,Object>>> flow() { return ApiResponse.ok(jdbc.queryForList("SELECT * FROM rpt_risk_flow ORDER BY source_level, flow_value DESC")); }

    private long scalar(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }
}
