package com.vtnexus.notebook.store.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthConfigController {

    private final String authMode;
    private final boolean allowUnverified;
    private final String oidcIssuer;
    private final String oidcClientId;
    private final List<String> oidcScopes;

    public AuthConfigController(
            @Value("${notebook.auth.mode:oidc}") String authMode,
            @Value("${notebook.auth.allow-unverified:false}") boolean allowUnverified,
            @Value("${notebook.auth.oidc.issuer:}") String oidcIssuer,
            @Value("${notebook.auth.oidc.client-id:kyuubi-notebook}") String oidcClientId,
            @Value("${notebook.auth.oidc.scopes:openid,profile}") List<String> oidcScopes) {
        this.authMode = authMode;
        this.allowUnverified = allowUnverified;
        this.oidcIssuer = oidcIssuer;
        this.oidcClientId = oidcClientId;
        this.oidcScopes = oidcScopes;
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getAuthConfig() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("mode", authMode);
        resp.put("verified", !allowUnverified);

        if ("oidc".equalsIgnoreCase(authMode)) {
            resp.put("issuer", oidcIssuer);
            resp.put("clientId", oidcClientId);
            resp.put("scopes", oidcScopes);
            resp.put("redirectUri", "/auth/callback");
        }
        return ResponseEntity.ok(resp);
    }
}
