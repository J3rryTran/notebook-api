package com.vtnexus.notebook.execution.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.vtnexus.notebook.common.auth.SessionInfo;
import com.vtnexus.notebook.common.auth.SessionResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
public class InternalSessionClient implements SessionResolver {

    private final String notebookServiceUrl;
    private final String internalToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Cache<String, SessionInfo> sessionCache;

    public InternalSessionClient(
            @Value("${notebook.notebook-service-url:http://localhost:8080}") String notebookServiceUrl,
            @Value("${notebook.internalToken:${notebook.internal-token}}") String internalToken,
            ObjectMapper objectMapper) {
        this.notebookServiceUrl = notebookServiceUrl.replaceAll("/+$", "");
        this.internalToken = internalToken;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.sessionCache = Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public SessionInfo resolve(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }

        SessionInfo cached = sessionCache.getIfPresent(sessionId);
        if (cached != null) {
            return cached;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(notebookServiceUrl + "/internal/auth/session?id=" + sessionId))
                    .header("X-Internal-Token", internalToken)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode json = objectMapper.readTree(response.body());
                String username = json.get("username").asText();
                String authHeader = json.get("authorizationHeader").asText();
                boolean isAdmin = json.path("isAdmin").asBoolean(false);

                SessionInfo info = new SessionInfo(username, authHeader, isAdmin);
                sessionCache.put(sessionId, info);
                return info;
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }
}
