package com.vtnexus.notebook.controller;

import com.vtnexus.notebook.model.NotebookExecution;
import com.vtnexus.notebook.model.NotebookSession;
import com.vtnexus.notebook.service.ExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class NotebookExecutionController {

    private final ExecutionService executionService;

    public NotebookExecutionController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    @PostMapping("/notebooks/{notebookId}/sessions")
    public ResponseEntity<NotebookSession> getOrCreateSession(@PathVariable("notebookId") String notebookId,
                                                              @AuthenticationPrincipal Object principal,
                                                              HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        return ResponseEntity.ok(executionService.getOrCreateSession(notebookId, principal.toString(), token));
    }

    @PostMapping("/notebook-sessions/{sessionId}/executions")
    public ResponseEntity<NotebookExecution> executeCell(@PathVariable("sessionId") String sessionId,
                                                         @RequestBody Map<String, String> body,
                                                         @AuthenticationPrincipal Object principal,
                                                         HttpServletRequest request) {
        String cellId = body.get("cellId");
        String token = request.getHeader("Authorization");
        return ResponseEntity.ok(executionService.executeCell(sessionId, cellId, principal.toString(), token));
    }

    @GetMapping("/executions/{executionId}")
    public ResponseEntity<NotebookExecution> getExecution(@PathVariable("executionId") String executionId,
                                                          @AuthenticationPrincipal Object principal) {
        return ResponseEntity.ok(executionService.getExecution(executionId, principal.toString()));
    }

    @PostMapping("/executions/{executionId}:cancel")
    public ResponseEntity<Void> cancelExecution(@PathVariable("executionId") String executionId,
                                                @AuthenticationPrincipal Object principal,
                                                HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        executionService.cancelExecution(executionId, principal.toString(), token);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/notebook-sessions/{sessionId}:restart")
    public ResponseEntity<Void> restartSession(@PathVariable("sessionId") String sessionId,
                                               @AuthenticationPrincipal Object principal,
                                               HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        executionService.restartSession(sessionId, principal.toString(), token);
        return ResponseEntity.noContent().build();
    }
}
