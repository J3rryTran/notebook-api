package com.vtnexus.notebook.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class SystemStatusController {

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getMe(@AuthenticationPrincipal Object principal) {
        String username = (principal != null) ? principal.toString() : "anonymous";
        Map<String, Object> resp = new HashMap<>();
        resp.put("user", username);
        resp.put("admin", "admin".equalsIgnoreCase(username));

        Map<String, Object> perms = new HashMap<>();
        perms.put("manageNotebooks", true);
        perms.put("manageRuntimes", true);
        perms.put("managePythonEnvironments", false);
        resp.put("permissions", perms);

        return ResponseEntity.ok(resp);
    }

    @GetMapping("/notebook-status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("notebookService", "HEALTHY");
        resp.put("persistence", "HEALTHY");
        resp.put("kyuubiSql", "HEALTHY");
        resp.put("pythonRuntimeManager", "HEALTHY");
        resp.put("activeSessions", 1);
        resp.put("activeRuntimes", 1);
        resp.put("queuedExecutions", 0);

        return ResponseEntity.ok(resp);
    }
}
