package com.vtnexus.notebook.common.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Filter bảo vệ tất cả endpoint /internal/** bằng X-Internal-Token header.
 * So sánh token bằng MessageDigest.isEqual để chống timing attacks (C18).
 * Kiểm tra tiền tố /internal/ chính xác (tránh bypass với /internalx).
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    private final String expectedToken;
    private final ObjectMapper objectMapper;

    public InternalTokenFilter(String expectedToken, ObjectMapper objectMapper) {
        this.expectedToken = expectedToken;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // Chỉ filter các request có path là /internal hoặc bắt đầu bằng /internal/
        return !(uri.equals("/internal") || uri.startsWith("/internal/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String token = request.getHeader("X-Internal-Token");
        if (token == null || expectedToken == null || expectedToken.isBlank() ||
                !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expectedToken.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), Map.of(
                    "code", "ACCESS_DENIED",
                    "message", "Invalid internal access token"
            ));
            return;
        }

        filterChain.doFilter(request, response);
    }
}
