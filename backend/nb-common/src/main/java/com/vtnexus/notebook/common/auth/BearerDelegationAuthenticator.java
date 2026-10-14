package com.vtnexus.notebook.common.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@Component
public class BearerDelegationAuthenticator {

    private final String kyuubiRestUrl;
    private final String usernameClaim;
    private final Duration cacheTtl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Cache<String, CachedPrincipal> tokenCache;

    public BearerDelegationAuthenticator(
            @Value("${kyuubi.rest-url:http://localhost:10099}") String kyuubiRestUrl,
            @Value("${notebook.auth.usernameClaim:preferred_username}") String usernameClaim,
            @Value("${notebook.auth.cacheTtl:60s}") Duration cacheTtl,
            ObjectMapper objectMapper) {
        this.kyuubiRestUrl = kyuubiRestUrl.replaceAll("/+$", "");
        this.usernameClaim = usernameClaim;
        this.cacheTtl = cacheTtl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.tokenCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(cacheTtl.toSeconds(), TimeUnit.SECONDS)
                .build();
    }

    public String authenticate(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new NotebookApiException("UNAUTHENTICATED", "Bearer token is required", HttpStatus.UNAUTHORIZED);
        }

        String cacheKey = sha256(authHeader);
        CachedPrincipal cached = tokenCache.getIfPresent(cacheKey);
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
            return cached.username;
        }

        // 1. Phân tích JWT payload trước để lấy usernameClaim và exp
        String token = authHeader.substring(7).trim();
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            throw new NotebookApiException("UNAUTHENTICATED", "Malformed JWT token", HttpStatus.UNAUTHORIZED);
        }

        String username;
        Instant expInstant;
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode payload = objectMapper.readTree(payloadBytes);

            if (!payload.has(usernameClaim) || payload.get(usernameClaim).asText().isBlank()) {
                throw new NotebookApiException("UNAUTHENTICATED", "Missing " + usernameClaim + " claim in token", HttpStatus.UNAUTHORIZED);
            }
            username = payload.get(usernameClaim).asText();

            long expSeconds = payload.path("exp").asLong(0);
            expInstant = expSeconds > 0 ? Instant.ofEpochSecond(expSeconds) : Instant.now().plus(cacheTtl);
        } catch (NotebookApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new NotebookApiException("UNAUTHENTICATED", "Failed to decode JWT payload: " + ex.getMessage(), HttpStatus.UNAUTHORIZED);
        }

        // 2. Gọi Kyuubi /api/v1/ping để xác thực token
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(kyuubiRestUrl + "/api/v1/ping"))
                    .header("Authorization", authHeader)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status == 200) {
                // Xác thực thành công: Lưu cache với thời gian min(cacheTtl, exp - now)
                Instant now = Instant.now();
                Duration remainingExp = Duration.between(now, expInstant);
                if (!remainingExp.isNegative() && !remainingExp.isZero()) {
                    tokenCache.put(cacheKey, new CachedPrincipal(username, expInstant));
                }
                return username;
            } else if (status == 401 || status == 403) {
                tokenCache.invalidate(cacheKey);
                throw new NotebookApiException("UNAUTHENTICATED", "Kyuubi rejected bearer token with status " + status, HttpStatus.UNAUTHORIZED);
            } else {
                throw new NotebookApiException("AUTH_BACKEND_UNAVAILABLE", "Kyuubi returned unexpected status " + status, HttpStatus.SERVICE_UNAVAILABLE);
            }
        } catch (NotebookApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new NotebookApiException("AUTH_BACKEND_UNAVAILABLE", "Failed to connect to Kyuubi auth backend: " + ex.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            return input;
        }
    }

    private static class CachedPrincipal {
        final String username;
        final Instant expiresAt;

        CachedPrincipal(String username, Instant expiresAt) {
            this.username = username;
            this.expiresAt = expiresAt;
        }
    }
}
