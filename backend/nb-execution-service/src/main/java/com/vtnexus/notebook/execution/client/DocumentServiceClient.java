package com.vtnexus.notebook.execution.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Component
public class DocumentServiceClient {

    private final RestTemplate restTemplate;
    private final String documentServiceUrl;
    private final String internalToken;

    public DocumentServiceClient(
            @Value("${notebook.document-service-url:http://localhost:8080}") String documentServiceUrl,
            @Value("${notebook.internal-token:dev-internal-secret-token}") String internalToken,
            RestTemplateBuilder builder) {
        this.documentServiceUrl = documentServiceUrl.endsWith("/") ? documentServiceUrl.substring(0, documentServiceUrl.length() - 1) : documentServiceUrl;
        this.internalToken = internalToken;
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
}
