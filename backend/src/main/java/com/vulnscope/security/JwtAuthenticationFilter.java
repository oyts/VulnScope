package com.vulnscope.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final JdbcTemplate jdbc;

    public JwtAuthenticationFilter(JwtService jwtService, JdbcTemplate jdbc) {
        this.jwtService = jwtService;
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = jwtService.parse(header.substring(7));
                long userId = ((Number) claims.get("uid")).longValue();
                List<Map<String, Object>> users = jdbc.queryForList(
                        "SELECT id, username, display_name, role FROM sys_user WHERE id=? AND status=1 AND deleted=0", userId);
                if (!users.isEmpty()) {
                    Map<String, Object> user = users.getFirst();
                    String role = String.valueOf(user.get("role"));
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            String.valueOf(user.get("username")), null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                    authentication.setDetails(Map.of("id", userId, "displayName", user.get("display_name"), "role", role));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (Exception ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
