package com.vtnexus.notebook.store.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    public MeController(@Value("${notebook.admins:}") List<String> admins) {
        this.admins = admins != null ? admins : List.of();
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getMe(@AuthenticationPrincipal Object principal) {
        String username = (principal != null) ? principal.toString() : "anonymous";
        boolean isAdmin = admins.contains(username);

        Map<String, Object> resp = new HashMap<>();
        resp.put("user", username);
        resp.put("admin", isAdmin);

        Map<String, Object> perms = new HashMap<>();
        perms.put("manageNotebooks", true);
        perms.put("manageRuntimes", true);
        perms.put("managePythonEnvironments", false);
        resp.put("permissions", perms);

        return ResponseEntity.ok(resp);
    }
}
