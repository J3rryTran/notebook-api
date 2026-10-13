package com.vtnexus.notebook.execution.controller;

import com.vtnexus.notebook.execution.model.NotebookExecution;
import com.vtnexus.notebook.execution.model.NotebookSession;
import com.vtnexus.notebook.execution.repository.NotebookExecutionRepository;
import com.vtnexus.notebook.execution.repository.NotebookSessionRepository;
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
    private final NotebookSessionRepository sessionRepository;
    private final NotebookExecutionRepository executionRepository;

    public NotebookExecutionController(
            ExecutionService executionService,
            NotebookSessionRepository sessionRepository,
            NotebookExecutionRepository executionRepository) {
        this.executionService = executionService;
        this.sessionRepository = sessionRepository;
        this.executionRepository = executionRepository;
    }

    @PostMapping("/notebooks/{notebookId}/sessions")
    public ResponseEntity<NotebookSession> getOrCreateSession(
            @PathVariable("notebookId") String notebookId,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        return ResponseEntity.ok(executionService.getOrCreateSession(notebookId, principal.toString(), auth));
    }

    @PostMapping("/notebook-sessions/{sessionId}/executions")
    public ResponseEntity<NotebookExecution> submitExecution(
            @PathVariable("sessionId") String sessionId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String cellId = body.get("cellId");
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        return ResponseEntity.ok(executionService.submitExecution(sessionId, cellId, principal.toString(), auth));
    }

    @GetMapping("/executions/{executionId}")
    public ResponseEntity<NotebookExecution> getExecution(
            @PathVariable("executionId") String executionId,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        return ResponseEntity.ok(executionService.syncAndGetExecution(executionId, principal.toString(), auth));
    }

    // Long-poll /events sử dụng Servlet DeferredResult (Q1 & Java 17 non-blocking)
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
                NotebookExecution exec = executionService.syncAndGetExecution(executionId, principal.toString(), auth);
                deferred.setResult(ResponseEntity.ok(Map.of(
                        "executionId", exec.getId(),
                        "status", exec.getStatus(),
                        "sequence", exec.getSequenceNumber()
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

    // Spec chuẩn F16: /api/v1/notebook-runtimes/{runtimeId}:restart
    @PostMapping("/notebook-runtimes/{runtimeId}:restart")
    public ResponseEntity<Void> restartRuntime(
            @PathVariable("runtimeId") String runtimeId,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        executionService.restartRuntime(runtimeId, principal.toString(), auth);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/executions")
    public ResponseEntity<List<NotebookExecution>> listExecutions(
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

    // S10: /notebook-status với số liệu thống kê thật từ database
    @GetMapping("/notebook-status")
    public ResponseEntity<Map<String, Object>> getNotebookStatus() {
        long activeSessions = sessionRepository.countByStatus("READY");
        long runningExecutions = executionRepository.countByStatus("RUNNING");

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
