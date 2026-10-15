package com.vtnexus.notebook.execution.controller;

import com.vtnexus.notebook.common.auth.KyuubiCredentialHolder;
import org.springframework.http.HttpStatus;
import com.vtnexus.notebook.common.exception.NotebookApiException;

import com.vtnexus.notebook.execution.dto.NotebookExecutionDto;
import com.vtnexus.notebook.execution.model.NotebookKyuubiSession;
import com.vtnexus.notebook.execution.repository.NotebookCellRunRepository;
import com.vtnexus.notebook.execution.repository.NotebookKyuubiSessionRepository;
import com.vtnexus.notebook.execution.service.ExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.*;
import java.util.concurrent.ForkJoinPool;

@RestController
@RequestMapping("/api/v1")
public class NotebookExecutionController {

    /**
     * Credential gửi Kyuubi cho request hiện tại: đúng credential mà filter xác thực vừa kiểm
     * (Bearer ở oidc, Basic giải mã từ phiên cookie ở no-oidc/ldap). Không đọc header Authorization
     * thô: ở mode cookie, header đó do client tự gửi và chưa được xác thực.
     */
    private static String kyuubiCredential() {
        String auth = KyuubiCredentialHolder.getAuthHeader();
        if (auth == null || auth.isBlank()) {
            throw new NotebookApiException("UNAUTHENTICATED", "Missing credential", HttpStatus.UNAUTHORIZED);
        }
        return auth;
    }


    private final ExecutionService executionService;
    private final NotebookKyuubiSessionRepository sessionRepository;
    private final NotebookCellRunRepository cellRunRepository;



    @PostMapping("/notebooks/{notebookId}/sessions")
    public ResponseEntity<Map<String, Object>> getOrCreateSession(
            @PathVariable("notebookId") String notebookId,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String auth = kyuubiCredential();

        Map<String, String> configs = null;
        if (body != null && body.containsKey("configs") && body.get("configs") instanceof Map<?, ?> map) {
            configs = new HashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    configs.put(e.getKey().toString(), e.getValue().toString());
                }
            }
        }

        ExecutionService.SessionCreationResult result = executionService.getOrCreateSession(
                notebookId, principal.toString(), auth, configs);

        Map<String, Object> resp = new HashMap<>();
        resp.put("session", result.session());
        resp.put("warnings", result.warnings());
        return ResponseEntity.ok(resp);
    }


    @PostMapping("/notebook-sessions/{sessionId}/executions")
    public ResponseEntity<NotebookExecutionDto> submitExecution(
            @PathVariable("sessionId") String sessionId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String cellId = body.get("cellId");
        String auth = kyuubiCredential();
        return ResponseEntity.ok(executionService.submitExecution(sessionId, cellId, principal.toString(), auth));
    }

    @GetMapping("/executions/{executionId}")
    public ResponseEntity<NotebookExecutionDto> getExecution(
            @PathVariable("executionId") String executionId,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String auth = kyuubiCredential();
        return ResponseEntity.ok(executionService.syncAndGetExecution(executionId, principal.toString(), auth));
    }

    private final com.vtnexus.notebook.execution.client.KyuubiRestClient kyuubiRestClient;
    private final org.springframework.core.task.AsyncTaskExecutor eventsTaskExecutor;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final javax.sql.DataSource dataSource;

    private volatile Boolean cachedKyuubiPing = null;
    private volatile long lastKyuubiPingTime = 0;

    public NotebookExecutionController(
            ExecutionService executionService,
            NotebookKyuubiSessionRepository sessionRepository,
            NotebookCellRunRepository cellRunRepository,
            com.vtnexus.notebook.execution.client.KyuubiRestClient kyuubiRestClient,
            @org.springframework.beans.factory.annotation.Qualifier("eventsTaskExecutor") org.springframework.core.task.AsyncTaskExecutor eventsTaskExecutor,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            javax.sql.DataSource dataSource) {
        this.executionService = executionService;
        this.sessionRepository = sessionRepository;
        this.cellRunRepository = cellRunRepository;
        this.kyuubiRestClient = kyuubiRestClient;
        this.eventsTaskExecutor = eventsTaskExecutor;
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
    }

    @GetMapping("/executions/{executionId}/events")
    public DeferredResult<ResponseEntity<Map<String, Object>>> getExecutionEvents(
            @PathVariable("executionId") String executionId,
            @RequestParam(value = "waitMillis", defaultValue = "3000") long waitMillis,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {

        long timeout = Math.min(Math.max(waitMillis, 500), 10000);
        DeferredResult<ResponseEntity<Map<String, Object>>> deferred = new DeferredResult<>(timeout);
        String auth = kyuubiCredential();

        deferred.onTimeout(() -> {
            deferred.setResult(ResponseEntity.noContent().build());
        });

        eventsTaskExecutor.execute(() -> {
            long startTime = System.currentTimeMillis();
            String lastStatus = null;
            try {
                while (!deferred.isSetOrExpired()) {
                    NotebookExecutionDto exec = executionService.syncAndGetExecution(executionId, principal.toString(), auth);
                    String currentStatus = exec.getStatus();
                    if (lastStatus != null && !lastStatus.equals(currentStatus)) {
                        deferred.setResult(ResponseEntity.ok(Map.of(
                                "executionId", exec.getId(),
                                "status", currentStatus
                        )));
                        return;
                    }
                    lastStatus = currentStatus;

                    if (!"PENDING".equals(currentStatus) && !"RUNNING".equals(currentStatus)) {
                        deferred.setResult(ResponseEntity.ok(Map.of(
                                "executionId", exec.getId(),
                                "status", currentStatus
                        )));
                        return;
                    }

                    if (System.currentTimeMillis() - startTime >= timeout - 100) {
                        break;
                    }

                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (!deferred.isSetOrExpired()) {
                    deferred.setResult(ResponseEntity.noContent().build());
                }
            } catch (Exception ex) {
                if (!deferred.isSetOrExpired()) {
                    deferred.setErrorResult(ex);
                }
            }
        });

        return deferred;
    }

    @PostMapping("/executions/{executionId}:cancel")
    public ResponseEntity<Void> cancelExecution(
            @PathVariable("executionId") String executionId,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String auth = kyuubiCredential();
        executionService.cancelExecution(executionId, principal.toString(), auth);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/notebook-runtimes/{runtimeId}:restart")
    public ResponseEntity<Void> restartRuntime(
            @PathVariable("runtimeId") String runtimeId,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String auth = kyuubiCredential();
        executionService.restartRuntime(runtimeId, principal.toString(), auth);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/executions/{executionId}/outputs")
    public ResponseEntity<String> getExecutionOutputs(
            @PathVariable("executionId") String executionId,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String auth = kyuubiCredential();
        NotebookExecutionDto exec = executionService.syncAndGetExecution(executionId, principal.toString(), auth);
        return ResponseEntity.ok(exec.getResultOutput() != null ? exec.getResultOutput() : "[]");
    }

    @GetMapping("/executions/{executionId}/logs")
    public ResponseEntity<Map<String, Object>> getExecutionLogs(
            @PathVariable("executionId") String executionId,
            @RequestParam(value = "afterSequence", defaultValue = "0") long afterSequence,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String auth = kyuubiCredential();
        executionService.syncAndGetExecution(executionId, principal.toString(), auth);
        String logs = executionService.getExecutionLogs(executionId, afterSequence);
        return ResponseEntity.ok(Map.of(
                "executionId", executionId,
                "logs", logs
        ));
    }

    @GetMapping("/executions/{executionId}/results")
    public ResponseEntity<String> getExecutionResults(
            @PathVariable("executionId") String executionId,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String auth = kyuubiCredential();
        NotebookExecutionDto exec = executionService.syncAndGetExecution(executionId, principal.toString(), auth);
        String outputJson = exec.getResultOutput();
        if (outputJson == null || outputJson.isBlank()) {
            return ResponseEntity.ok("[]");
        }

        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(outputJson);
            com.fasterxml.jackson.databind.JsonNode rowsNode = root.at("/data/application~1vnd.notebook.table+json/rows");
            if (rowsNode.isArray()) {
                com.fasterxml.jackson.databind.node.ArrayNode rows = (com.fasterxml.jackson.databind.node.ArrayNode) rowsNode;
                int total = rows.size();
                int from = Math.min(Math.max(offset, 0), total);
                int to = Math.min(from + Math.max(limit, 0), total);

                com.fasterxml.jackson.databind.node.ArrayNode slicedRows = objectMapper.createArrayNode();
                for (int i = from; i < to; i++) {
                    slicedRows.add(rows.get(i));
                }

                com.fasterxml.jackson.databind.node.ObjectNode copy = root.deepCopy();
                com.fasterxml.jackson.databind.JsonNode tableNode = copy.at("/data/application~1vnd.notebook.table+json");
                if (tableNode.isObject()) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) tableNode).set("rows", slicedRows);
                }
                return ResponseEntity.ok(objectMapper.writeValueAsString(copy));
            }
        } catch (Exception ignored) {}

        return ResponseEntity.ok(outputJson);
    }

    @DeleteMapping("/notebook-sessions/{sessionId}")
    public ResponseEntity<Void> closeSession(
            @PathVariable("sessionId") String sessionId,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String auth = kyuubiCredential();
        executionService.closeSession(sessionId, principal.toString(), auth);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/notebook-sessions/{sessionId}/runtimes")
    public ResponseEntity<Map<String, Object>> getSessionRuntimes(
            @PathVariable("sessionId") String sessionId,
            @AuthenticationPrincipal Object principal) {
        NotebookKyuubiSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new com.vtnexus.notebook.common.exception.NotebookApiException("NOTEBOOK_SESSION_NOT_FOUND", "Session not found", org.springframework.http.HttpStatus.NOT_FOUND));
        if (!session.getUserId().equals(principal.toString())) {
            throw new com.vtnexus.notebook.common.exception.NotebookApiException("NOTEBOOK_SESSION_NOT_FOUND", "Session not found", org.springframework.http.HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.ok(Map.of(
                "sessionId", session.getId(),
                "status", session.getStatus(),
                "instance", session.getKyuubiInstance() != null ? session.getKyuubiInstance() : ""
        ));
    }

    @PostMapping("/notebook-runtimes/{runtimeId}:interrupt")
    public ResponseEntity<Void> interruptRuntime(
            @PathVariable("runtimeId") String runtimeId,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String auth = kyuubiCredential();
        executionService.interruptSession(runtimeId, principal.toString(), auth);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/executions")
    public ResponseEntity<List<NotebookExecutionDto>> listExecutions(
            @RequestParam("notebookId") String notebookId,
            @RequestParam("cellId") String cellId,
            @AuthenticationPrincipal Object principal) {
        return ResponseEntity.ok(executionService.getLatestExecutions(notebookId, cellId, principal.toString()));
    }

    @GetMapping("/runtime-specs")
    public ResponseEntity<List<Map<String, Object>>> getRuntimeSpecs() {
        return ResponseEntity.ok(List.of(
                Map.of("id", "spark-pyspark-default", "name", "Apache Spark (PySpark & SQL)", "status", "AVAILABLE")
        ));
    }

    @GetMapping("/notebook-status")
    public ResponseEntity<Map<String, Object>> getNotebookStatus(
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String user = principal != null ? principal.toString() : "anonymous";
        String auth = kyuubiCredential();

        // 1. Kiểm tra kyuubi /ping cache 10s
        long now = System.currentTimeMillis();
        boolean kyuubiOk;
        if (cachedKyuubiPing != null && (now - lastKyuubiPingTime < 10000)) {
            kyuubiOk = cachedKyuubiPing;
        } else {
            kyuubiOk = kyuubiRestClient.ping(auth);
            cachedKyuubiPing = kyuubiOk;
            lastKyuubiPingTime = now;
        }

        // 2. Persistence check SELECT 1
        boolean persistenceOk = false;
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
            persistenceOk = true;
        } catch (Exception ignored) {}

        // 3. Active / queued runs theo user
        long activeCount = cellRunRepository.countByUserAndStatus(user, "RUNNING");
        long queuedCount = cellRunRepository.countByUserAndStatus(user, "PENDING");
        long userSessions = sessionRepository.countByUserIdAndStatusIn(user, java.util.List.of("READY", "BUSY"));

        Map<String, Object> resp = new HashMap<>();
        resp.put("kyuubi", kyuubiOk ? "HEALTHY" : "UNHEALTHY");
        resp.put("persistence", persistenceOk ? "HEALTHY" : "UNHEALTHY");
        resp.put("active", activeCount);
        resp.put("queued", queuedCount);
        resp.put("activeSessions", userSessions);

        return ResponseEntity.ok(resp);
    }
}

