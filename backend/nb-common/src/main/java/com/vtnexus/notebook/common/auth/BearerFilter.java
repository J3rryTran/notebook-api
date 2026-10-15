package com.vtnexus.notebook.common.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vtnexus.notebook.common.dto.ErrorEnvelope;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BearerFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BearerFilter.class);

    private final BearerDelegationAuthenticator authenticator;
    private final ObjectMapper objectMapper;

    public BearerFilter(BearerDelegationAuthenticator authenticator, ObjectMapper objectMapper) {
        this.authenticator = authenticator;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        if (isPublicOrInternal(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || authHeader.isBlank()) {
            writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authorization header is required");
            return;
        }

        if (!authHeader.startsWith("Bearer ")) {
            writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Bearer token is required in OIDC mode. Basic authentication is not permitted.");
            return;
        }

        String username;
        try {
            username = authenticator.authenticate(authHeader);
        } catch (NotebookApiException ex) {
            writeError(response, ex.getStatus(), ex.getCode(), ex.getMessage());
            return;
        } catch (Exception ex) {
            String reqId = request.getHeader("X-Request-Id");
            log.warn("Internal authentication error [reqId={}]: {}", reqId, ex.getMessage());
            writeError(response, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Authentication error");
            return;
        }

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        KyuubiCredentialHolder.set(authHeader, username);
        try {
            filterChain.doFilter(request, response);
        } finally {
            KyuubiCredentialHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private boolean isPublicOrInternal(String path) {
        return path.startsWith("/actuator")
                || path.startsWith("/internal")
                || path.equals("/api/v1/auth/config")
                || path.equals("/error");
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorEnvelope envelope = ErrorEnvelope.of(code, message);
        response.getWriter().write(objectMapper.writeValueAsString(envelope));
    }
}
