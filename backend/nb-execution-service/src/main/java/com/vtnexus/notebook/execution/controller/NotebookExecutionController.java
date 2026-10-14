package com.vtnexus.notebook.execution.controller;

import com.vtnexus.notebook.execution.dto.NotebookExecutionDto;
import com.vtnexus.notebook.execution.model.NotebookKyuubiSession;
import com.vtnexus.notebook.execution.repository.NotebookCellRunRepository;
import com.vtnexus.notebook.execution.repository.NotebookKyuubiSessionRepository;
import com.vtnexus.notebook.execution.service.ExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.*;
import java.util.concurrent.ForkJoinPool;

@RestController
@RequestMapping("/api/v1")
public class NotebookExecutionController {

    private final ExecutionService executionService;
    private final NotebookKyuubiSessionRepository sessionRepository;
    private final NotebookCellRunRepository cellRunRepository;

    public NotebookExecutionController(
            ExecutionService executionService,
            NotebookKyuubiSessionRepository sessionRepository,
            NotebookCellRunRepository cellRunRepository) {
        this.executionService = executionService;
        this.sessionRepository = sessionRepository;
        this.cellRunRepository = cellRunRepository;
    }

    @PostMapping("/notebooks/{notebookId}/sessions")
    public ResponseEntity<NotebookKyuubiSession> getOrCreateSession(
            @PathVariable("notebookId") String notebookId,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        return ResponseEntity.ok(executionService.getOrCreateSession(notebookId, principal.toString(), auth));
    }

    @PostMapping("/notebook-sessions/{sessionId}/executions")
    public ResponseEntity<NotebookExecutionDto> submitExecution(
            @PathVariable("sessionId") String sessionId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String cellId = body.get("cellId");
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        return ResponseEntity.ok(executionService.submitExecution(sessionId, cellId, principal.toString(), auth));
    }

    @GetMapping("/executions/{executionId}")
    public ResponseEntity<NotebookExecutionDto> getExecution(
            @PathVariable("executionId") String executionId,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        return ResponseEntity.ok(executionService.syncAndGetExecution(executionId, principal.toString(), auth));
    }

    @GetMapping("/executions/{executionId}/events")
    public DeferredResult<ResponseEntity<Map<String, Object>>> getExecutionEvents(
            @PathVariable("executionId") String executionId,
            @RequestParam(value = "waitMillis", defaultValue = "3000") long waitMillis,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {

        long timeout = Math.min(Math.max(waitMillis, 500), 10000);
        DeferredResult<ResponseEntity<Map<String, Object>>> deferred = new DeferredResult<>(timeout);
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);

        deferred.onTimeout(() -> {
            deferred.setResult(ResponseEntity.ok(Map.of("timeout", true)));
        });

        ForkJoinPool.commonPool().submit(() -> {
            try {
                NotebookExecutionDto exec = executionService.syncAndGetExecution(executionId, principal.toString(), auth);
                deferred.setResult(ResponseEntity.ok(Map.of(
                        "executionId", exec.getId(),
                        "status", exec.getStatus()
                )));
            } catch (Exception ex) {
                deferred.setErrorResult(ex);
            }
        });

        return deferred;
    }

    @PostMapping("/executions/{executionId}:cancel")
    public ResponseEntity<Void> cancelExecution(
            @PathVariable("executionId") String executionId,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        executionService.cancelExecution(executionId, principal.toString(), auth);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/notebook-runtimes/{runtimeId}:restart")
    public ResponseEntity<Void> restartRuntime(
            @PathVariable("runtimeId") String runtimeId,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        executionService.restartRuntime(runtimeId, principal.toString(), auth);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/executions/{executionId}/outputs")
    public ResponseEntity<String> getExecutionOutputs(
            @PathVariable("executionId") String executionId,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        NotebookExecutionDto exec = executionService.syncAndGetExecution(executionId, principal.toString(), auth);
        return ResponseEntity.ok(exec.getResultOutput() != null ? exec.getResultOutput() : "[]");
    }

    @GetMapping("/executions/{executionId}/logs")
    public ResponseEntity<Map<String, Object>> getExecutionLogs(
            @PathVariable("executionId") String executionId,
            @RequestParam(value = "afterSequence", defaultValue = "0") long afterSequence,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        executionService.syncAndGetExecution(executionId, principal.toString(), auth);
        String logs = executionService.getExecutionLogs(executionId);
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
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        NotebookExecutionDto exec = executionService.syncAndGetExecution(executionId, principal.toString(), auth);
        return ResponseEntity.ok(exec.getResultOutput() != null ? exec.getResultOutput() : "[]");
    }

    @DeleteMapping("/notebook-sessions/{sessionId}")
    public ResponseEntity<Void> closeSession(
            @PathVariable("sessionId") String sessionId,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        NotebookKyuubiSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null && session.getUserId().equals(principal.toString())) {
            session.setStatus("CLOSED");
            sessionRepository.save(session);
        }
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
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        executionService.cancelExecution(runtimeId, principal.toString(), auth);
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
    public ResponseEntity<Map<String, Object>> getNotebookStatus() {
        long activeSessions = sessionRepository.count();
        long runningExecutions = cellRunRepository.count();

        Map<String, Object> resp = new HashMap<>();
        resp.put("notebookService", "HEALTHY");
        resp.put("persistence", "HEALTHY");
        resp.put("kyuubiSql", "HEALTHY");
        resp.put("pythonRuntimeManager", "HEALTHY");
        resp.put("activeSessions", activeSessions);
        resp.put("activeRuntimes", activeSessions);
        resp.put("queuedExecutions", runningExecutions);
        return ResponseEntity.ok(resp);
    }
}
