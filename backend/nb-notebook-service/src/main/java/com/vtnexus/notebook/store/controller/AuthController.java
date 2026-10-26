package com.vtnexus.notebook.store.controller;

import com.vtnexus.notebook.common.auth.AuthMode;
import com.vtnexus.notebook.common.auth.AuthProperties;
import com.vtnexus.notebook.common.auth.SessionCookieFilter;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import com.vtnexus.notebook.store.model.NotebookAuthSession;
import com.vtnexus.notebook.store.service.AuthSessionService;
import com.vtnexus.notebook.store.service.LdapAuthenticator;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthProperties authProperties;
    private final AuthSessionService sessionService;
    private final LdapAuthenticator ldapAuthenticator;
    private final com.vtnexus.notebook.common.config.NotebookProperties notebookProperties;
    private final String kyuubiRestUrl;
    private final HttpClient httpClient;
    private final org.springframework.core.env.Environment environment;
    private final com.vtnexus.notebook.store.service.OidcLoginService oidcLoginService;

    public AuthController(
            AuthProperties authProperties,
            AuthSessionService sessionService,
            com.vtnexus.notebook.store.service.OidcLoginService oidcLoginService,
            LdapAuthenticator ldapAuthenticator,
            com.vtnexus.notebook.common.config.KyuubiProperties kyuubiProperties,
            com.vtnexus.notebook.common.config.NotebookProperties notebookProperties,
            org.springframework.core.env.Environment environment) {
        this.authProperties = authProperties;
        this.sessionService = sessionService;
        this.oidcLoginService = oidcLoginService;
        this.ldapAuthenticator = ldapAuthenticator;
        this.notebookProperties = notebookProperties;
        this.kyuubiRestUrl = kyuubiProperties.getRestUrl().replaceAll("/+$", "");
        this.environment = environment;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private boolean isDev() {
        return environment.acceptsProfiles(org.springframework.core.env.Profiles.of("dev"));
    }

    private boolean isSecureCookie(HttpServletRequest request) {
        return !isDev() && request.isSecure();
    }

    private void addSessionCookie(HttpServletRequest request, HttpServletResponse response, String sessionId, Duration maxAge) {
        org.springframework.http.ResponseCookie cookie = org.springframework.http.ResponseCookie.from(SessionCookieFilter.COOKIE_NAME, sessionId)
                .httpOnly(true)
                .secure(isSecureCookie(request))
                .sameSite(AuthMode.fromString(authProperties.getMode()) == AuthMode.OIDC ? "Lax" : "Strict")
                .path("/")
                .maxAge(maxAge)
                .build();
        response.addHeader(org.springframework.http.HttpHeaders.SET_COOKIE, cookie.toString());
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody Map<String, String> body,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {

        AuthMode mode = AuthMode.fromString(authProperties.getMode());
        if (mode == AuthMode.OIDC) {
            throw new NotebookApiException("METHOD_NOT_ALLOWED", "Login endpoint is disabled in OIDC mode", HttpStatus.METHOD_NOT_ALLOWED);
        }

        String username = body != null ? body.get("username") : null;
        String password = body != null ? body.get("password") : null;
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new NotebookApiException("INVALID_CREDENTIALS", "Username and password are required", HttpStatus.UNAUTHORIZED);
        }

        String authUser;
        boolean isAdmin = false;

        if (mode == AuthMode.NO_OIDC) {
            String raw = username + ":" + password;
            String basicHeader = "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(kyuubiRestUrl + "/api/v1/ping"))
                        .header("Authorization", basicHeader)
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    authUser = username;
                } else if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                    throw new NotebookApiException("INVALID_CREDENTIALS", "Invalid credentials", HttpStatus.UNAUTHORIZED);
                } else {
                    throw new NotebookApiException("AUTH_BACKEND_UNAVAILABLE", "Failed to contact Kyuubi", HttpStatus.SERVICE_UNAVAILABLE);
                }
            } catch (NotebookApiException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new NotebookApiException("AUTH_BACKEND_UNAVAILABLE", "Failed to contact Kyuubi", HttpStatus.SERVICE_UNAVAILABLE);
            }
        } else if (mode == AuthMode.LDAP) {
            LdapAuthenticator.LdapUser ldapUser = ldapAuthenticator.authenticate(username, password);
            authUser = ldapUser.canonicalUsername();
            isAdmin = ldapUser.isAdmin();
        } else {
            throw new NotebookApiException("INVALID_REQUEST", "Unsupported auth mode", HttpStatus.BAD_REQUEST);
        }

        NotebookAuthSession session = sessionService.createSession(authUser, password, mode.getValue(), isAdmin);

        addSessionCookie(httpRequest, response, session.getId(), authProperties.getSessionTtl());

        return ResponseEntity.ok(Map.of(
                "user", authUser,
                "message", "Login successful"
        ));
    }

    @GetMapping("/oidc/login")
    public ResponseEntity<Void> oidcLogin(
            @RequestParam(value = "redirect", required = false) String redirect,
            HttpServletRequest request) {
        requireOidc();
        String location = oidcLoginService.buildAuthorizationRedirect(request, redirect);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(org.springframework.http.HttpHeaders.LOCATION, location)
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> oidcCallback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            HttpServletRequest request,
            HttpServletResponse response) {
        requireOidc();
        String target;
        if (error != null && !error.isBlank()) {
            target = "/?authError=" + java.net.URLEncoder.encode(error, StandardCharsets.UTF_8);
        } else {
            try {
                com.vtnexus.notebook.store.service.OidcLoginService.LoginResult result = oidcLoginService.completeLogin(code, state);
                addSessionCookie(request, response, result.session().getId(), authProperties.getOidc().getSessionTimeout());
                target = result.redirectPath();
            } catch (NotebookApiException ex) {
                org.slf4j.LoggerFactory.getLogger(AuthController.class).warn("OIDC callback failed: {}", ex.getMessage());
                target = "/?authError=" + java.net.URLEncoder.encode(ex.getCode().toLowerCase(), StandardCharsets.UTF_8);
            }
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(org.springframework.http.HttpHeaders.LOCATION, target)
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    private void requireOidc() {
        if (AuthMode.fromString(authProperties.getMode()) != AuthMode.OIDC) {
            throw new NotebookApiException("NOT_FOUND", "OIDC login is not enabled", HttpStatus.NOT_FOUND);
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            HttpServletRequest request,
            HttpServletResponse response) {

        AuthMode mode = AuthMode.fromString(authProperties.getMode());
        String sessionId = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (SessionCookieFilter.COOKIE_NAME.equals(c.getName())) {
                    sessionId = c.getValue();
                    break;
                }
            }
        }

        java.util.Optional<String> idToken = mode == AuthMode.OIDC
                ? sessionService.oidcIdToken(sessionId)
                : java.util.Optional.empty();
        if (sessionId != null) {
            sessionService.deleteSession(sessionId);
            addSessionCookie(request, response, "", Duration.ZERO);
        }

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("message", "Logout successful");
        if (mode == AuthMode.OIDC) {
            oidcLoginService.endSessionUrl(idToken, oidcLoginService.postLogoutRedirect(request))
                    .ifPresent(url -> body.put("endSessionUrl", url));
        }
        return ResponseEntity.ok(body);
    }
}
