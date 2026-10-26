package com.vtnexus.notebook.store.controller;

import com.vtnexus.notebook.common.auth.AuthProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthConfigController {

    private final AuthProperties authProperties;
    private final com.vtnexus.notebook.common.config.NotebookProperties notebookProperties;

    public AuthConfigController(AuthProperties authProperties, com.vtnexus.notebook.common.config.NotebookProperties notebookProperties) {
        this.authProperties = authProperties;
        this.notebookProperties = notebookProperties;
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getAuthConfig() {
        Map<String, Object> resp = new HashMap<>();
        String mode = authProperties.getMode();
        resp.put("mode", mode);

        if ("oidc".equalsIgnoreCase(mode)) {
            resp.put("verified", authProperties.isEffectiveVerified());
            resp.put("loginUrl", "/api/v1/auth/oidc/login");
        } else if ("no-oidc".equalsIgnoreCase(mode)) {
            resp.put("verified", authProperties.isEffectiveVerified());
        } else if ("ldap".equalsIgnoreCase(mode)) {
            resp.put("verified", true);
        }

        resp.put("message", "Auth configuration retrieved successfully");
        return ResponseEntity.ok(resp);
    }
}
