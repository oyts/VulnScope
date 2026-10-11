package com.vulnscope.admin;

import com.vulnscope.common.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final JdbcTemplate jdbc;

    public AdminController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/users")
    public ApiResponse<List<Map<String,Object>>> users() {
        return ApiResponse.ok(jdbc.queryForList("SELECT id,username,display_name,role,status,created_at,updated_at FROM sys_user WHERE deleted=0 ORDER BY id"));
    }

    @PatchMapping("/users/{id}/status")
    public ApiResponse<Void> userStatus(@PathVariable long id, @RequestBody StatusRequest request) {
        if (request.status() != 0 && request.status() != 1) throw new IllegalArgumentException("状态只能为0或1");
        int rows = jdbc.update("UPDATE sys_user SET status=? WHERE id=? AND role='USER' AND deleted=0", request.status(), id);
        if (rows == 0) throw new IllegalArgumentException("普通用户不存在");
        return ApiResponse.ok("状态已更新", null);
    }

    @GetMapping("/data-sources")
    public ApiResponse<List<Map<String,Object>>> dataSources() {
        return ApiResponse.ok(jdbc.queryForList("SELECT * FROM data_source ORDER BY source_code"));
    }

    @GetMapping("/analysis-tasks")
    public ApiResponse<List<Map<String,Object>>> tasks() {
        return ApiResponse.ok(jdbc.queryForList("SELECT * FROM analysis_task ORDER BY created_at DESC LIMIT 100"));
    }

    @GetMapping("/audit-logs")
    public ApiResponse<List<Map<String,Object>>> auditLogs() {
        return ApiResponse.ok(jdbc.queryForList("SELECT * FROM audit_log ORDER BY created_at DESC LIMIT 200"));
    }

    public record StatusRequest(int status) {}
}
