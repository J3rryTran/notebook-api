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
import org.springframework.beans.factory.annotation.Value;
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
    private final String kyuubiRestUrl;
    private final HttpClient httpClient;

    public AuthController(
            AuthProperties authProperties,
            AuthSessionService sessionService,
            LdapAuthenticator ldapAuthenticator,
            @Value("${kyuubi.rest-url:http://localhost:10099}") String kyuubiRestUrl) {
        this.authProperties = authProperties;
        this.sessionService = sessionService;
        this.ldapAuthenticator = ldapAuthenticator;
        this.kyuubiRestUrl = kyuubiRestUrl.replaceAll("/+$", "");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody Map<String, String> body,
            HttpServletResponse response) {

        AuthMode mode = AuthMode.fromString(authProperties.getMode());
        if (mode == AuthMode.OIDC) {
            throw new NotebookApiException("METHOD_NOT_ALLOWED", "Login endpoint is disabled in OIDC mode", HttpStatus.METHOD_NOT_ALLOWED);
        }

        String username = body.get("username");
        String password = body.get("password");
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new NotebookApiException("INVALID_CREDENTIALS", "Username and password are required", HttpStatus.UNAUTHORIZED);
        }

        String authUser;
        boolean isAdmin = false;

        if (mode == AuthMode.NO_OIDC) {
            // Xác thực với Kyuubi qua Basic header
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
                    throw new NotebookApiException("AUTH_BACKEND_UNAVAILABLE", "Kyuubi returned status " + resp.statusCode(), HttpStatus.SERVICE_UNAVAILABLE);
                }
            } catch (NotebookApiException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new NotebookApiException("AUTH_BACKEND_UNAVAILABLE", "Failed to contact Kyuubi: " + ex.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
            }
        } else if (mode == AuthMode.LDAP) {
            LdapAuthenticator.LdapUser ldapUser = ldapAuthenticator.authenticate(username, password);
            authUser = ldapUser.canonicalUsername();
            isAdmin = ldapUser.isAdmin();
        } else {
            throw new NotebookApiException("INVALID_REQUEST", "Unsupported auth mode", HttpStatus.BAD_REQUEST);
        }

        NotebookAuthSession session = sessionService.createSession(authUser, password, mode.getValue(), isAdmin);

        Cookie cookie = new Cookie(SessionCookieFilter.COOKIE_NAME, session.getId());
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge((int) Duration.parse("PT" + authProperties.getSessionTtl()).toSeconds());
        response.addCookie(cookie);

        return ResponseEntity.ok(Map.of("user", authUser));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest request,
            HttpServletResponse response) {

        AuthMode mode = AuthMode.fromString(authProperties.getMode());
        if (mode == AuthMode.OIDC) {
            throw new NotebookApiException("METHOD_NOT_ALLOWED", "Logout endpoint is disabled in OIDC mode", HttpStatus.METHOD_NOT_ALLOWED);
        }

        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (SessionCookieFilter.COOKIE_NAME.equals(c.getName())) {
                    sessionService.deleteSession(c.getValue());
                    Cookie clearCookie = new Cookie(SessionCookieFilter.COOKIE_NAME, "");
                    clearCookie.setHttpOnly(true);
                    clearCookie.setPath("/");
                    clearCookie.setMaxAge(0);
                    response.addCookie(clearCookie);
                    break;
                }
            }
        }
        return ResponseEntity.ok().build();
    }
}
