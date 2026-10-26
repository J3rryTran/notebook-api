package com.vtnexus.notebook.common.auth;

import com.vtnexus.notebook.common.exception.NotebookApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class OidcTokenVerifiers {

    private final AuthProperties authProperties;
    private volatile JwtTokenVerifier accessTokenVerifier;
    private volatile JwtTokenVerifier idTokenVerifier;

    public OidcTokenVerifiers(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    public JwtTokenVerifier accessToken() {
        JwtTokenVerifier v = accessTokenVerifier;
        if (v == null) {
            synchronized (this) {
                if (accessTokenVerifier == null) {
                    AuthProperties.Jwt jwt = authProperties.getOidc().getJwt();
                    accessTokenVerifier = new JwtTokenVerifier(jwt, audiences(jwt.getAudience()));
                }
                v = accessTokenVerifier;
            }
        }
        return v;
    }

    public JwtTokenVerifier idToken() {
        JwtTokenVerifier v = idTokenVerifier;
        if (v == null) {
            synchronized (this) {
                if (idTokenVerifier == null) {
                    AuthProperties.OidcProperties oidc = authProperties.getOidc();
                    idTokenVerifier = new JwtTokenVerifier(oidc.getJwt(), Set.of(oidc.getUi().getClientId().trim()));
                }
                v = idTokenVerifier;
            }
        }
        return v;
    }

    public String authenticateBearer(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new NotebookApiException("UNAUTHENTICATED", "Bearer token is required", HttpStatus.UNAUTHORIZED);
        }
        return accessToken().verifyAndGetUsername(authorizationHeader.substring(7).trim());
    }

    public void setForTesting(JwtTokenVerifier access, JwtTokenVerifier id) {
        this.accessTokenVerifier = access;
        this.idTokenVerifier = id;
    }

    private static Set<String> audiences(java.util.List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String a : raw) {
                if (a != null && !a.isBlank()) {
                    out.add(a.trim());
                }
            }
        }
        return out;
    }
}
