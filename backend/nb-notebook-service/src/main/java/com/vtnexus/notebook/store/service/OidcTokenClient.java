package com.vtnexus.notebook.store.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vtnexus.notebook.common.auth.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class OidcTokenClient {

    private static final Logger log = LoggerFactory.getLogger(OidcTokenClient.class);

    public record Tokens(String accessToken, String refreshToken, String idToken, long expiresInSeconds) {}

    public static class TokenEndpointException extends RuntimeException {
        private final String error;

        public TokenEndpointException(String error, String message) {
            super(message);
            this.error = error;
        }

        public String error() {
            return error;
        }
    }

    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OidcTokenClient(AuthProperties authProperties, ObjectMapper objectMapper) {
        this.authProperties = authProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(authProperties.getOidc().getJwt().getConnectTimeout())
                .build();
    }

    public Tokens exchangeCode(String code, String redirectUri, String codeVerifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("code_verifier", codeVerifier);
        return post(form);
    }

    public Tokens refresh(String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        return post(form);
    }

    private Tokens post(Map<String, String> form) {
        AuthProperties.OidcProperties oidc = authProperties.getOidc();
        form.put("client_id", oidc.getUi().getClientId());
        form.put("client_secret", oidc.getUi().getClientSecret());
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));

        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(oidc.getTokenEndpoint().trim()))
                    .timeout(oidc.getJwt().getReadTimeout())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TokenEndpointException("temporarily_unavailable", "Token endpoint call interrupted");
        } catch (Exception e) {
            log.warn("OIDC token endpoint unreachable: {}", e.toString());
            throw new TokenEndpointException("temporarily_unavailable", "Token endpoint unreachable");
        }

        JsonNode json;
        try {
            json = objectMapper.readTree(response.body());
        } catch (Exception e) {
            json = null;
        }

        if (response.statusCode() / 100 != 2 || json == null || !json.hasNonNull("access_token")) {
            String error = json != null && json.hasNonNull("error") ? json.get("error").asText() : "http_" + response.statusCode();
            log.warn("OIDC token endpoint returned {} ({})", response.statusCode(), error);
            throw new TokenEndpointException(error, "Token endpoint rejected the request: " + error);
        }

        return new Tokens(
                json.get("access_token").asText(),
                json.hasNonNull("refresh_token") ? json.get("refresh_token").asText() : null,
                json.hasNonNull("id_token") ? json.get("id_token").asText() : null,
                json.path("expires_in").asLong(300));
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }
}
