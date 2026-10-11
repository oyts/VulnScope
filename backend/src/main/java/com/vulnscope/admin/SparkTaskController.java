package com.vulnscope.admin;

import com.vulnscope.common.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/analysis-tasks")
public class SparkTaskController {
    private final SparkTaskService service;

    public SparkTaskController(SparkTaskService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> submit(@RequestBody(required = false) SubmitRequest request,
                                                   Authentication authentication) {
        String dimension = request == null ? "ALL" : request.dimensionCode();
        return ApiResponse.ok("Spark任务已进入队列", service.submit(currentUserId(authentication), dimension));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) {
        return ApiResponse.ok(service.task(id));
    }

    @SuppressWarnings("unchecked")
    private long currentUserId(Authentication authentication) {
        return ((Number) ((Map<String, Object>) authentication.getDetails()).get("id")).longValue();
    }

    public record SubmitRequest(String dimensionCode) {}
}
