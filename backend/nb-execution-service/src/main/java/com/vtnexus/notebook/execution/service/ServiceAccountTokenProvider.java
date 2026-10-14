package com.vtnexus.notebook.execution.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;

@Component
public class ServiceAccountTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceAccountTokenProvider.class);

    private final RestTemplate restTemplate;
    private final String issuer;
    private final String clientId;
    private final String clientSecret;

    private String cachedToken;
    private Instant tokenExpiry = Instant.MIN;

    public ServiceAccountTokenProvider(
            @Value("${notebook.auth.oidc.issuer:}") String issuer,
            @Value("${notebook.auth.oidc.serviceClientId:notebook-api}") String clientId,
            @Value("${notebook.auth.oidc.serviceClientSecret:${NOTEBOOK_OIDC_CLIENT_SECRET:}}") String clientSecret,
            RestTemplateBuilder builder) {
        this.issuer = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }

    public synchronized String getServiceAccountToken() {
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new NotebookApiException("PROXY_UNAVAILABLE", "OIDC service account client secret is not configured", HttpStatus.SERVICE_UNAVAILABLE);
        }

        Instant now = Instant.now();
        if (cachedToken != null && now.isBefore(tokenExpiry)) {
            return cachedToken;
        }

        String tokenUrl = issuer + "/protocol/openid-connect/token";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    tokenUrl, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

            JsonNode respBody = response.getBody();
            if (respBody == null || !respBody.has("access_token")) {
                throw new NotebookApiException("PROXY_UNAVAILABLE", "Failed to obtain service account token", HttpStatus.SERVICE_UNAVAILABLE);
            }

            String accessToken = respBody.get("access_token").asText();
            int expiresIn = respBody.has("expires_in") ? respBody.get("expires_in").asInt(300) : 300;
            // Cache token tới exp - 30s
            this.cachedToken = accessToken;
            this.tokenExpiry = now.plusSeconds(Math.max(10, expiresIn - 30));

            return accessToken;
        } catch (NotebookApiException nae) {
            throw nae;
        } catch (Exception ex) {
            log.warn("Failed to request service account token from {}: {}", tokenUrl, ex.getMessage());
            throw new NotebookApiException("PROXY_UNAVAILABLE", "Keycloak service account authentication failed: " + ex.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
