package com.vtnexus.notebook.common.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vtnexus.notebook.common.dto.ErrorEnvelope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Filter ủy quyền xác thực cho Kyuubi:
 * Không tự kiểm JWT, không tự gọi LDAP, không dùng X-User hay user mặc định.
 * Gọi GET <kyuubi>/api/v1/ping với header Authorization nhận được.
 */
@Component
public class KyuubiAuthDelegationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(KyuubiAuthDelegationFilter.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String kyuubiBaseUrl;
    private final String usernameClaim;
    private final long cacheTtlSeconds;

    // Cache kết quả xác thực theo SHA-256(Authorization Header)
    private final Map<String, CacheEntry> authCache = new ConcurrentHashMap<>();

    private record CacheEntry(String username, Instant expiresAt) {}

    public KyuubiAuthDelegationFilter(
            @Value("${kyuubi.rest-url:http://localhost:10099}") String kyuubiBaseUrl,
            @Value("${notebook.auth.username-claim:preferred_username}") String usernameClaim,
            @Value("${notebook.auth.cache-ttl:60}") long cacheTtlSeconds,
            RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper) {
        this.kyuubiBaseUrl = kyuubiBaseUrl.endsWith("/") ? kyuubiBaseUrl.substring(0, kyuubiBaseUrl.length() - 1) : kyuubiBaseUrl;
        this.usernameClaim = usernameClaim;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
                || path.equals("/api/v1/notebook-status")
                || path.startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        // 1. Nếu không có header Authorization hoặc không phải Bearer -> 401 UNAUTHENTICATED
        if (authHeader == null || authHeader.isBlank() || !authHeader.startsWith("Bearer ")) {
            writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Missing or invalid Bearer Authorization header");
            return;
        }

        String cacheKey = sha256(authHeader);
        CacheEntry cached = authCache.get(cacheKey);
        String username = null;

        if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
            username = cached.username;
        } else {
            // 2. Gọi GET <kyuubi>/api/v1/ping với header của request
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, authHeader);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            try {
                ResponseEntity<String> kyuubiResp = restTemplate.exchange(
                        kyuubiBaseUrl + "/api/v1/ping", HttpMethod.GET, entity, String.class);

                if (kyuubiResp.getStatusCode().is2xxSuccessful()) {
                    username = extractUsername(authHeader);
                    authCache.put(cacheKey, new CacheEntry(username, Instant.now().plusSeconds(cacheTtlSeconds)));
                } else {
                    writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Kyuubi rejected credentials");
                    return;
                }
            } catch (HttpStatusCodeException ex) {
                if (ex.getStatusCode() == HttpStatus.UNAUTHORIZED || ex.getStatusCode() == HttpStatus.FORBIDDEN) {
                    writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Invalid or expired token");
                } else {
                    writeError(response, HttpStatus.BAD_GATEWAY, "KYUUBI_ERROR", "Kyuubi returned error: " + ex.getStatusCode());
                }
                return;
            } catch (ResourceAccessException ex) {
                // Kyuubi không trả lời -> 503 AUTH_BACKEND_UNAVAILABLE
                log.error("Kyuubi unreachable during auth ping: {}", ex.getMessage());
                writeError(response, HttpStatus.SERVICE_UNAVAILABLE, "AUTH_BACKEND_UNAVAILABLE", "Authentication backend Kyuubi is unavailable");
                return;
            } catch (Exception ex) {
                log.error("Auth delegation failure", ex);
                writeError(response, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Authentication error");
                return;
            }
        }

        if (username == null) {
            writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Token is missing claim '" + usernameClaim + "'");
            return;
        }

        // Set Security Context
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username, authHeader, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }

    private String extractUsername(String authHeader) {
        if (authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            String[] parts = token.split("\\.");
            if (parts.length >= 2) {
                try {
                    byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
                    JsonNode json = objectMapper.readTree(payloadBytes);
                    if (json.has(usernameClaim)) {
                        String user = json.get(usernameClaim).asText();
                        if (!user.isBlank()) {
                            return user;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String reqId = UUID.randomUUID().toString();
        ErrorEnvelope env = ErrorEnvelope.of(code, message, reqId, status == HttpStatus.SERVICE_UNAVAILABLE, Collections.emptyMap());
        response.getOutputStream().write(objectMapper.writeValueAsBytes(env));
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
