package com.vulnscope.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrap implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String password;

    public AdminBootstrap(JdbcTemplate jdbc, PasswordEncoder encoder,
                          @Value("${app.admin-password}") String password) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.password = password;
    }

    @Override
    public void run(String... args) {
        if (password == null || password.isBlank()) return;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE role='ADMIN' AND deleted=0", Integer.class);
        if (count != null && count == 0) {
            jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,role,status) VALUES('admin',?,'系统管理员','ADMIN',1)",
                    encoder.encode(password));
        }
    }
}
