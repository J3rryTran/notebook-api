package com.vtnexus.notebook.execution.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * KyuubiRestClient giao tiếp chuẩn với Kyuubi:
 * - Bỏ toàn bộ fallback giả (mock-session, mock-op, FINISHED/OK).
 * - S4: Gọi đúng host kyuubiInstance khi pinToInstance = true.
 * - Map lỗi rõ ràng: 503 KYUUBI_UNAVAILABLE, 401 KYUUBI_AUTH_FAILED, 409 RUNTIME_LOST.
 */
@Component
public class KyuubiRestClient {

    private static final Logger log = LoggerFactory.getLogger(KyuubiRestClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String defaultBaseUrl;
    private final boolean pinToInstance;
    private final Map<String, String> defaultSessionConfigs;
    private final List<String> allowedSessionConfigKeys;

    public KyuubiRestClient(
            @Value("${kyuubi.rest-url:http://localhost:10099}") String defaultBaseUrl,
            @Value("${kyuubi.pin-to-instance:true}") boolean pinToInstance,
            @Value("${kyuubi.allowed-session-config-keys:spark.executor.memory,spark.executor.cores,spark.sql.shuffle.partitions}") List<String> allowedSessionConfigKeys,
            RestTemplateBuilder builder,
            ObjectMapper objectMapper) {
        this.defaultBaseUrl = defaultBaseUrl.endsWith("/") ? defaultBaseUrl.substring(0, defaultBaseUrl.length() - 1) : defaultBaseUrl;
        this.pinToInstance = pinToInstance;
        this.allowedSessionConfigKeys = allowedSessionConfigKeys;
        this.defaultSessionConfigs = Map.of("kyuubi.engine.spark.output.mode", "NOTEBOOK");
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
        this.objectMapper = objectMapper;
    }

    private String getBaseUrl(String instance) {
        if (pinToInstance && instance != null && !instance.isBlank()) {
            return instance.startsWith("http") ? instance : "http://" + instance;
        }
        return defaultBaseUrl;
    }

    public Map<String, String> openSession(String userAuthHeader, Map<String, String> extraConfigs) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (userAuthHeader != null) headers.set(HttpHeaders.AUTHORIZATION, userAuthHeader);

        Map<String, String> configs = new HashMap<>(defaultSessionConfigs);
        if (extraConfigs != null) {
            for (Map.Entry<String, String> e : extraConfigs.entrySet()) {
                if (!allowedSessionConfigKeys.contains(e.getKey())) {
                    throw new NotebookApiException("INVALID_REQUEST", "Config key not allowed: " + e.getKey(), HttpStatus.UNPROCESSABLE_ENTITY);
                }
                configs.put(e.getKey(), e.getValue());
            }
        }

        Map<String, Object> body = Map.of("configs", configs);

        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(defaultBaseUrl + "/api/v1/sessions", entity, JsonNode.class);

            JsonNode respBody = response.getBody();
            if (respBody == null || !respBody.has("identifier")) {
                throw new NotebookApiException("KYUUBI_ERROR", "Invalid response from Kyuubi open session", HttpStatus.BAD_GATEWAY);
            }

            String sessionId = respBody.get("identifier").asText();
            String instance = respBody.has("kyuubiInstance") ? respBody.get("kyuubiInstance").asText() : null;

            return Map.of("sessionId", sessionId, "instance", instance != null ? instance : "");

        } catch (HttpStatusCodeException ex) {
            handleHttpStatusException(ex);
            throw new NotebookApiException("KYUUBI_ERROR", ex.getResponseBodyAsString(), HttpStatus.BAD_GATEWAY);
        } catch (ResourceAccessException ex) {
            throw new NotebookApiException("KYUUBI_UNAVAILABLE", "Cannot connect to Kyuubi: " + ex.getMessage(), HttpStatus.SERVICE_UNAVAILABLE, true, null);
        }
    }

    public String submitStatement(String sessionId, String instance, String statement, String language, String userAuthHeader) {
        String baseUrl = getBaseUrl(instance);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (userAuthHeader != null) headers.set(HttpHeaders.AUTHORIZATION, userAuthHeader);

        Map<String, Object> body = new HashMap<>();
        body.put("statement", statement);
        body.put("runAsync", true);
        body.put("queryTimeout", 0);

        if ("PYTHON".equalsIgnoreCase(language)) {
            body.put("confOverlay", Map.of("kyuubi.operation.language", "PYTHON"));
        }

        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                    baseUrl + "/api/v1/sessions/" + sessionId + "/operations/statement", entity, JsonNode.class);

            JsonNode respBody = response.getBody();
            if (respBody == null || !respBody.has("identifier")) {
                throw new NotebookApiException("KYUUBI_ERROR", "Failed to retrieve operation ID from Kyuubi", HttpStatus.BAD_GATEWAY);
            }
            return respBody.get("identifier").asText();

        } catch (HttpStatusCodeException ex) {
            handleHttpStatusException(ex);
            throw new NotebookApiException("KYUUBI_ERROR", ex.getResponseBodyAsString(), HttpStatus.BAD_GATEWAY);
        } catch (ResourceAccessException ex) {
            throw new NotebookApiException("KYUUBI_UNAVAILABLE", "Kyuubi unavailable: " + ex.getMessage(), HttpStatus.SERVICE_UNAVAILABLE, true, null);
        }
    }

    public JsonNode getOperationEvent(String operationId, String instance, String userAuthHeader) {
        String baseUrl = getBaseUrl(instance);
        HttpHeaders headers = new HttpHeaders();
        if (userAuthHeader != null) headers.set(HttpHeaders.AUTHORIZATION, userAuthHeader);

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    baseUrl + "/api/v1/operations/" + operationId + "/event",
                    HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
            return response.getBody();
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new NotebookApiException("RUNTIME_LOST", "Operation not found on Kyuubi engine", HttpStatus.CONFLICT);
            }
            handleHttpStatusException(ex);
            throw new NotebookApiException("KYUUBI_ERROR", ex.getMessage(), HttpStatus.BAD_GATEWAY);
        } catch (ResourceAccessException ex) {
            throw new NotebookApiException("KYUUBI_UNAVAILABLE", "Kyuubi connection error", HttpStatus.SERVICE_UNAVAILABLE, true, null);
        }
    }

    public JsonNode getOperationRowset(String operationId, String instance, int maxRows, String userAuthHeader) {
        String baseUrl = getBaseUrl(instance);
        HttpHeaders headers = new HttpHeaders();
        if (userAuthHeader != null) headers.set(HttpHeaders.AUTHORIZATION, userAuthHeader);

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    baseUrl + "/api/v1/operations/" + operationId + "/rowset?maxrows=" + maxRows + "&fetchorientation=FETCH_NEXT",
                    HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
            return response.getBody();
        } catch (Exception ex) {
            log.error("Failed to fetch rowset: {}", ex.getMessage());
            return null;
        }
    }

    public void closeOperation(String operationId, String instance, String userAuthHeader) {
        String baseUrl = getBaseUrl(instance);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (userAuthHeader != null) headers.set(HttpHeaders.AUTHORIZATION, userAuthHeader);
            restTemplate.exchange(baseUrl + "/api/v1/operations/" + operationId, HttpMethod.PUT,
                    new HttpEntity<>(Map.of("action", "close"), headers), Void.class);
        } catch (Exception ignored) {}
    }

    public void cancelOperation(String operationId, String instance, String userAuthHeader) {
        String baseUrl = getBaseUrl(instance);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (userAuthHeader != null) headers.set(HttpHeaders.AUTHORIZATION, userAuthHeader);
            restTemplate.exchange(baseUrl + "/api/v1/operations/" + operationId, HttpMethod.PUT,
                    new HttpEntity<>(Map.of("action", "cancel"), headers), Void.class);
        } catch (Exception ignored) {}
    }

    public void closeSession(String sessionId, String instance, String userAuthHeader) {
        String baseUrl = getBaseUrl(instance);
        try {
            HttpHeaders headers = new HttpHeaders();
            if (userAuthHeader != null) headers.set(HttpHeaders.AUTHORIZATION, userAuthHeader);
            restTemplate.exchange(baseUrl + "/api/v1/sessions/" + sessionId, HttpMethod.DELETE,
                    new HttpEntity<>(headers), Void.class);
        } catch (Exception ignored) {}
    }

    private void handleHttpStatusException(HttpStatusCodeException ex) {
        if (ex.getStatusCode() == HttpStatus.UNAUTHORIZED || ex.getStatusCode() == HttpStatus.FORBIDDEN) {
            throw new NotebookApiException("KYUUBI_AUTH_FAILED", "Kyuubi authentication rejected", HttpStatus.UNAUTHORIZED);
        }
        if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
            throw new NotebookApiException("RUNTIME_LOST", "Kyuubi session or engine runtime lost", HttpStatus.CONFLICT);
        }
    }
}
