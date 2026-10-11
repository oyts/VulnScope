package com.vulnscope.auth;

import com.vulnscope.common.ApiResponse;
import com.vulnscope.security.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;

    public AuthController(JdbcTemplate jdbc, PasswordEncoder encoder, JwtService jwtService) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?", Integer.class, request.username());
        if (exists != null && exists > 0) throw new IllegalArgumentException("用户名已存在");
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,role,status) VALUES(?,?,?,'USER',1)",
                request.username(), encoder.encode(request.password()), request.displayName());
        return ApiResponse.ok("注册成功", Map.of("username", request.username(), "role", "USER"));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,username,password_hash,display_name,role,status FROM sys_user WHERE username=? AND deleted=0", request.username());
        if (rows.isEmpty() || !encoder.matches(request.password(), String.valueOf(rows.getFirst().get("password_hash")))) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        Map<String, Object> row = rows.getFirst();
        if (((Number) row.get("status")).intValue() != 1) throw new IllegalArgumentException("账号已被禁用");
        long id = ((Number) row.get("id")).longValue();
        String role = String.valueOf(row.get("role"));
        String token = jwtService.create(id, request.username(), role);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("user", Map.of("id", id, "username", request.username(), "displayName", row.get("display_name"), "role", role));
        return ApiResponse.ok(data);
    }

    @GetMapping("/me")
    public ApiResponse<Object> me(Authentication authentication) {
        return ApiResponse.ok(authentication.getDetails());
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record RegisterRequest(@NotBlank @Size(min=4,max=50) String username,
                                  @NotBlank @Size(min=6,max=72) String password,
                                  @NotBlank @Size(max=50) String displayName) {}
}
