package com.vtnexus.notebook.common.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vtnexus.notebook.common.dto.ErrorEnvelope;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SessionCookieFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionCookieFilter.class);

    public static final String COOKIE_NAME = "NB_SESSION";


    private final SessionResolver sessionResolver;
    private final ObjectMapper objectMapper;

    public SessionCookieFilter(SessionResolver sessionResolver, ObjectMapper objectMapper) {
        this.sessionResolver = sessionResolver;
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

        String sessionId = extractSessionCookie(request);
        if (sessionId == null || sessionId.isBlank()) {
            writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Missing " + COOKIE_NAME + " session cookie");
            return;
        }

        try {
            SessionInfo info = sessionResolver.resolve(sessionId);
            if (info == null) {
                writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Session expired or invalid");
                return;
            }

            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            if (info.isAdmin()) {
                authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
            }

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    info.username(),
                    null,
                    authorities
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            KyuubiCredentialHolder.set(info.authorizationHeader(), info.username());

            filterChain.doFilter(request, response);
        } catch (NotebookApiException ex) {
            writeError(response, ex.getStatus(), ex.getCode(), ex.getMessage());
        } catch (Exception ex) {
            String reqId = request.getHeader("X-Request-Id");
            log.warn("Session resolution failed [reqId={}]: {}", reqId, ex.getMessage());
            writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Session resolution failed");
        } finally {

            KyuubiCredentialHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private boolean isPublicOrInternal(String path) {
        return path.startsWith("/actuator")
                || path.startsWith("/internal")
                || path.equals("/api/v1/auth/config")
                || path.equals("/api/v1/auth/login")
                || path.equals("/error");
    }

    private String extractSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE_NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorEnvelope envelope = ErrorEnvelope.of(code, message);
        response.getWriter().write(objectMapper.writeValueAsString(envelope));
    }
}
