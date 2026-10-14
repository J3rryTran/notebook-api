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

    public AuthConfigController(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getAuthConfig() {
        Map<String, Object> resp = new HashMap<>();
        String mode = authProperties.getMode();
        resp.put("mode", mode);

        if ("oidc".equalsIgnoreCase(mode)) {
            resp.put("verified", authProperties.isEffectiveVerified());
            resp.put("issuer", authProperties.getOidc().getIssuer());
            resp.put("clientId", authProperties.getOidc().getClientId());
            resp.put("scopes", authProperties.getOidc().getScopes());
            resp.put("redirectUri", "/auth/callback");
        } else if ("no-oidc".equalsIgnoreCase(mode)) {
            resp.put("verified", authProperties.isEffectiveVerified());
        } else if ("ldap".equalsIgnoreCase(mode)) {
            resp.put("verified", true);
        }


        return ResponseEntity.ok(resp);
    }
}
