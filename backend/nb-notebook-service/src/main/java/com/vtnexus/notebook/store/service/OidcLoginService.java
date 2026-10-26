package com.vtnexus.notebook.store.service;

import com.nimbusds.jwt.JWTClaimsSet;
import com.vtnexus.notebook.common.auth.AuthProperties;
import com.vtnexus.notebook.common.auth.AuthPropertiesValidator;
import com.vtnexus.notebook.common.auth.OidcTokenVerifiers;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import com.vtnexus.notebook.store.model.NotebookAuthSession;
import com.vtnexus.notebook.store.model.NotebookOidcPendingLogin;
import com.vtnexus.notebook.store.repository.NotebookOidcPendingLoginRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class OidcLoginService {

    private static final Logger log = LoggerFactory.getLogger(OidcLoginService.class);
    static final Duration PENDING_LOGIN_TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    public record LoginResult(NotebookAuthSession session, String redirectPath) {}

    private final AuthProperties authProperties;
    private final NotebookOidcPendingLoginRepository pendingRepository;
    private final OidcTokenClient tokenClient;
    private final OidcTokenVerifiers verifiers;
    private final AuthSessionService sessionService;

    public OidcLoginService(
            AuthProperties authProperties,
            NotebookOidcPendingLoginRepository pendingRepository,
            OidcTokenClient tokenClient,
            OidcTokenVerifiers verifiers,
            AuthSessionService sessionService) {
        this.authProperties = authProperties;
        this.pendingRepository = pendingRepository;
        this.tokenClient = tokenClient;
        this.verifiers = verifiers;
        this.sessionService = sessionService;
    }

    @Transactional
    public String buildAuthorizationRedirect(HttpServletRequest request, String rawRedirect) {
        AuthProperties.OidcProperties oidc = authProperties.getOidc();
        String state = randomToken();
        String nonce = randomToken();
        String verifier = randomToken() + randomToken();
        String redirectUri = redirectUri(request);
        Instant now = Instant.now();

        pendingRepository.save(NotebookOidcPendingLogin.builder()
                .state(state)
                .codeVerifier(verifier)
                .nonce(nonce)
                .redirectPath(sanitizeRedirectPath(rawRedirect))
                .redirectUri(redirectUri)
                .createdAt(now)
                .expiresAt(now.plus(PENDING_LOGIN_TTL))
                .build());

        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", oidc.getUi().getClientId());
        query.put("redirect_uri", redirectUri);
        query.put("scope", withOpenIdScope(oidc.getUi().getScope()));
        query.put("state", state);
        query.put("nonce", nonce);
        query.put("code_challenge", codeChallengeS256(verifier));
        query.put("code_challenge_method", "S256");
        String endpoint = oidc.getAuthorizationEndpoint().trim();
        return endpoint + (endpoint.contains("?") ? "&" : "?") + formEncode(query);
    }

    public LoginResult completeLogin(String code, String state) {
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            throw invalid("Missing authorization code or state");
        }
        NotebookOidcPendingLogin pending = pendingRepository.findById(state).orElse(null);
        if (pending == null || pendingRepository.deleteByState(state) != 1) {
            throw invalid("Unknown or already used login state");
        }
        if (pending.getExpiresAt().isBefore(Instant.now())) {
            throw invalid("Login request expired");
        }

        OidcTokenClient.Tokens tokens;
        try {
            tokens = tokenClient.exchangeCode(code, pending.getRedirectUri(), pending.getCodeVerifier());
        } catch (OidcTokenClient.TokenEndpointException ex) {
            if ("temporarily_unavailable".equals(ex.error())) {
                throw new NotebookApiException("AUTH_BACKEND_UNAVAILABLE", "Identity provider is unavailable", HttpStatus.SERVICE_UNAVAILABLE);
            }
            throw invalid("Token exchange failed (" + ex.error() + ")");
        }
        if (tokens.idToken() == null) {
            throw invalid("Token response carried no id_token; the 'openid' scope must be granted");
        }

        JWTClaimsSet idClaims = verifiers.idToken().verify(tokens.idToken());
        String nonce;
        try {
            nonce = idClaims.getStringClaim("nonce");
        } catch (ParseException e) {
            nonce = null;
        }
        if (nonce == null || !MessageDigest.isEqual(nonce.getBytes(StandardCharsets.UTF_8), pending.getNonce().getBytes(StandardCharsets.UTF_8))) {
            throw invalid("id_token nonce does not match the login request");
        }

        String username = verifiers.accessToken().verifyAndGetUsername(tokens.accessToken());
        NotebookAuthSession session = sessionService.createOidcSession(username, tokens);
        log.info("Established OIDC session for '{}'", username);
        return new LoginResult(session, pending.getRedirectPath());
    }

    public Optional<String> endSessionUrl(Optional<String> idToken, String postLogoutRedirect) {
        AuthProperties.OidcProperties oidc = authProperties.getOidc();
        String endpoint = oidc.getEndSessionEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("post_logout_redirect_uri", postLogoutRedirect);
        query.put("client_id", oidc.getUi().getClientId());
        idToken.ifPresent(t -> query.put("id_token_hint", t));
        String e = endpoint.trim();
        return Optional.of(e + (e.contains("?") ? "&" : "?") + formEncode(query));
    }

    public String redirectUri(HttpServletRequest request) {
        String configured = authProperties.getOidc().getRedirectUri();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return requestOrigin(request) + AuthPropertiesValidator.OIDC_CALLBACK_PATH;
    }

    public String postLogoutRedirect(HttpServletRequest request) {
        String configured = authProperties.getOidc().getRedirectUri();
        if (configured != null && !configured.isBlank()) {
            java.net.URI uri = java.net.URI.create(configured.trim());
            return uri.getScheme() + "://" + uri.getRawAuthority() + "/";
        }
        return requestOrigin(request) + "/";
    }

    @Scheduled(fixedDelayString = "${notebook.auth.cleanup-interval:600000}")
    @Transactional
    public void cleanupExpiredPendingLogins() {
        pendingRepository.deleteExpired(Instant.now());
    }

    static String sanitizeRedirectPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return "/";
        }
        String p = raw.trim();
        if (!p.startsWith("/") || p.startsWith("//") || p.contains("\\") || p.contains("://")
                || p.startsWith("/api/") || p.startsWith("/internal/") || p.startsWith("/actuator/")
                || p.length() > 1000) {
            return "/";
        }
        return p;
    }

    static String withOpenIdScope(String scope) {
        String s = scope == null ? "" : scope.trim();
        for (String part : s.split("\\s+")) {
            if ("openid".equals(part)) {
                return s;
            }
        }
        return s.isEmpty() ? "openid" : "openid " + s;
    }

    static String codeChallengeS256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String requestOrigin(HttpServletRequest request) {
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + request.getServerName() + (defaultPort || port <= 0 ? "" : ":" + port);
    }

    private static String randomToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String formEncode(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static NotebookApiException invalid(String message) {
        return new NotebookApiException("OIDC_LOGIN_FAILED", message, HttpStatus.UNAUTHORIZED);
    }
}
