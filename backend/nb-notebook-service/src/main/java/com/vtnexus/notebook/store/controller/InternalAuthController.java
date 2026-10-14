package com.vtnexus.notebook.store.controller;

import com.vtnexus.notebook.common.auth.AuthMode;
import com.vtnexus.notebook.common.auth.AuthProperties;
import com.vtnexus.notebook.common.auth.SessionInfo;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import com.vtnexus.notebook.store.service.AuthSessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/internal/auth")
public class InternalAuthController {

    private final AuthProperties authProperties;
    private final AuthSessionService sessionService;
    private final String internalToken;

    public InternalAuthController(
            AuthProperties authProperties,
            AuthSessionService sessionService,
            @Value("${notebook.internalToken:${notebook.internal-token:}}") String internalToken) {
        this.authProperties = authProperties;
        this.sessionService = sessionService;
        this.internalToken = internalToken;
    }

    private void verifyInternalToken(String token) {
        if (token == null || internalToken == null || !token.equals(internalToken)) {
            throw new NotebookApiException("ACCESS_DENIED", "Invalid internal access token", HttpStatus.UNAUTHORIZED);
        }
    }

    @GetMapping("/session")
    public ResponseEntity<Map<String, Object>> getSession(
            @RequestParam("id") String sessionId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {

        verifyInternalToken(token);

        AuthMode mode = AuthMode.fromString(authProperties.getMode());
        if (mode == AuthMode.OIDC) {
            throw new NotebookApiException("NOT_FOUND", "Session lookup is disabled in OIDC mode", HttpStatus.NOT_FOUND);
        }

        SessionInfo info = sessionService.resolveSession(sessionId);
        if (info == null) {
            throw new NotebookApiException("SESSION_NOT_FOUND", "Session expired or not found", HttpStatus.NOT_FOUND);
        }

        return ResponseEntity.ok(Map.of(
                "username", info.username(),
                "authorizationHeader", info.authorizationHeader(),
                "isAdmin", info.isAdmin()
        ));
    }
}
