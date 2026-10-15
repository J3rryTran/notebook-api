package com.vtnexus.notebook.execution.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Component
public class DocumentServiceClient {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceClient.class);

    private final RestTemplate restTemplate;
    private final String documentServiceUrl;
    private final String internalToken;

    public DocumentServiceClient(
            com.vtnexus.notebook.common.config.NotebookProperties notebookProperties,
            RestTemplateBuilder builder) {
        String url = notebookProperties.getNotebookServiceUrl();
        this.documentServiceUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.internalToken = notebookProperties.getInternalToken();
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean canExecute(String notebookId, String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", internalToken);

        try {
            ResponseEntity<JsonNode> resp = restTemplate.exchange(
                    documentServiceUrl + "/internal/notebooks/" + notebookId + "/access?user=" + user,
                    HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

            JsonNode body = resp.getBody();
            return body != null && body.has("canExecute") && body.get("canExecute").asBoolean();
        } catch (Exception ex) {
            throw new NotebookApiException("ACCESS_DENIED", "Cannot verify notebook execution access", HttpStatus.FORBIDDEN);
        }
    }

    public JsonNode getCell(String notebookId, String cellId, String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", internalToken);

        try {
            ResponseEntity<JsonNode> resp = restTemplate.exchange(
                    documentServiceUrl + "/internal/notebooks/" + notebookId + "/cells/" + cellId + "?user=" + user,
                    HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
            return resp.getBody();
        } catch (Exception ex) {
            throw new NotebookApiException("CELL_NOT_FOUND", "Cell not found: " + cellId, HttpStatus.NOT_FOUND);
        }
    }

    private final com.github.benmanes.caffeine.cache.Cache<String, String> rangerCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .expireAfterWrite(Duration.ofMinutes(5))
                    .maximumSize(10_000)
                    .build();

    /**
     * Tra {@code app_users.ranger_username} (B4). Lỗi tra cứu thì báo lỗi, không rơi về username và không cache:
     * rơi về username sẽ cho người dùng chạy sai danh tính trên Ranger.
     */
    public String getRangerUsername(String user) {
        if (user == null || user.isBlank()) return user;
        return rangerCache.get(user, u -> {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Internal-Token", internalToken);
            ResponseEntity<JsonNode> resp;
            try {
                resp = restTemplate.exchange(
                        documentServiceUrl + "/internal/users/{username}/ranger-username",
                        HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class, u);
            } catch (Exception ex) {
                log.warn("Ranger username lookup failed for {}: {}", u, ex.getMessage());
                throw new NotebookApiException("RANGER_LOOKUP_FAILED", "Cannot resolve Ranger user", HttpStatus.SERVICE_UNAVAILABLE);
            }
            JsonNode body = resp.getBody();
            String r = body != null ? body.path("rangerUsername").asText("") : "";
            if (r.isBlank()) {
                throw new NotebookApiException("RANGER_LOOKUP_FAILED", "Cannot resolve Ranger user", HttpStatus.SERVICE_UNAVAILABLE);
            }
            return r;
        });
    }
}
