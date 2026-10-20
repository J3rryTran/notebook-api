package com.vtnexus.notebook.store.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class MeController {

    private final List<String> admins;

    public MeController(com.vtnexus.notebook.common.config.NotebookProperties notebookProperties) {
        this.admins = notebookProperties.getAdmins() != null ? notebookProperties.getAdmins() : List.of();
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getMe(@AuthenticationPrincipal Object principal) {
        String username = (principal != null) ? principal.toString() : "anonymous";

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean hasAdminRole = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));

        boolean isAdmin = hasAdminRole || admins.contains(username);

        Map<String, Object> resp = new HashMap<>();
        resp.put("user", username);
        resp.put("admin", isAdmin);

        Map<String, Object> perms = new HashMap<>();
        perms.put("manageNotebooks", true);
        perms.put("manageRuntimes", true);
        perms.put("managePythonEnvironments", false);
        resp.put("permissions", perms);
        resp.put("message", "Current user retrieved successfully");

        return ResponseEntity.ok(resp);
    }
}
