package com.vtnexus.notebook.store.service;

import com.vtnexus.notebook.common.auth.AuthProperties;
import com.vtnexus.notebook.common.auth.CredentialCrypto;
import com.vtnexus.notebook.common.auth.LdapServiceAccount;
import com.vtnexus.notebook.common.auth.SessionInfo;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import com.vtnexus.notebook.store.model.NotebookAuthSession;
import com.vtnexus.notebook.store.repository.NotebookAuthSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class AuthSessionService {

    private static final Logger log = LoggerFactory.getLogger(AuthSessionService.class);

    private final NotebookAuthSessionRepository sessionRepository;
    private final CredentialCrypto credentialCrypto;
    private final AuthProperties authProperties;
    private final LdapAuthenticator ldapAuthenticator;
    private final String kyuubiRestUrl;
    private final HttpClient httpClient;
    private final List<String> admins;
    private final OidcTokenClient oidcTokenClient;
    private final com.vtnexus.notebook.common.auth.OidcTokenVerifiers oidcTokenVerifiers;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    static final long OIDC_REFRESH_SKEW_MS = 60_000L;

    public AuthSessionService(
            NotebookAuthSessionRepository sessionRepository,
            AuthProperties authProperties,
            LdapAuthenticator ldapAuthenticator,
            com.vtnexus.notebook.common.config.NotebookProperties notebookProperties,
            com.vtnexus.notebook.common.config.KyuubiProperties kyuubiProperties,
            OidcTokenClient oidcTokenClient,
            com.vtnexus.notebook.common.auth.OidcTokenVerifiers oidcTokenVerifiers,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.oidcTokenClient = oidcTokenClient;
        this.oidcTokenVerifiers = oidcTokenVerifiers;
        this.objectMapper = objectMapper;
        this.authProperties = authProperties;
        this.ldapAuthenticator = ldapAuthenticator;
        this.kyuubiRestUrl = kyuubiProperties.getRestUrl().replaceAll("/+$", "");
        this.admins = (notebookProperties.getAdmins() != null) ? notebookProperties.getAdmins() : List.of();
        String encKey = notebookProperties.getEncryptionKey();
        this.credentialCrypto = (encKey != null && !encKey.isBlank())
                ? new CredentialCrypto(encKey)
                : null;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public NotebookAuthSession createSession(String username, String password, String authMode, boolean isAdmin) {
        byte[] enc = null;
        if (!isLdap(authMode)) {
            if (credentialCrypto == null) {
                throw new IllegalStateException("Encryption key is not configured for session storage");
            }
            enc = credentialCrypto.encrypt(username + ":" + password);
        }
        Instant now = Instant.now();

        boolean adminFlag = isAdmin || admins.contains(username);

        NotebookAuthSession session = NotebookAuthSession.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .username(username)
                .authMode(authMode)
                .credentialEnc(enc)
                .isAdmin(adminFlag)
                .createdAt(now)
                .lastSeenAt(now)
                .lastRecheckAt(now)
                .expiresAt(now.plus(authProperties.getSessionTtl()))
                .build();

        return sessionRepository.save(session);
    }

    public SessionInfo resolveSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }

        Optional<NotebookAuthSession> opt = sessionRepository.findById(sessionId);
        if (opt.isEmpty()) {
            return null;
        }

        NotebookAuthSession session = opt.get();
        Instant now = Instant.now();

        if (session.getExpiresAt().isBefore(now)) {
            sessionRepository.delete(session);
            return null;
        }

        if (isLdap(session.getAuthMode())) {
            return resolveLdapSession(session, now);
        }
        if (isOidc(session.getAuthMode())) {
            return resolveOidcSession(session, now);
        }
        if (credentialCrypto == null || session.getCredentialEnc() == null) {
            sessionRepository.delete(session);
            return null;
        }

        String rawCred;
        try {
            rawCred = credentialCrypto.decrypt(session.getCredentialEnc());
        } catch (Exception e) {
            sessionRepository.delete(session);
            return null;
        }

        Duration timeSinceRecheck = Duration.between(session.getLastRecheckAt(), now);
        if (timeSinceRecheck.compareTo(authProperties.getRecheck()) > 0) {
            boolean recheckPassed = false;
            boolean backendUnavailable = false;

            if ("no-oidc".equalsIgnoreCase(session.getAuthMode())) {
                String basicAuth = "Basic " + Base64.getEncoder().encodeToString(rawCred.getBytes(StandardCharsets.UTF_8));
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(kyuubiRestUrl + "/api/v1/ping"))
                            .header("Authorization", basicAuth)
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build();
                    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) {
                        recheckPassed = true;
                    } else if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                        recheckPassed = false;
                    } else {
                        backendUnavailable = true;
                    }
                } catch (Exception ex) {
                    log.warn("Kyuubi unavailable during session recheck: {}", ex.getMessage());
                    backendUnavailable = true;
                }
            } else {
                recheckPassed = true;
            }

            if (!backendUnavailable && !recheckPassed) {
                log.info("Session recheck failed for user {}, invalidating session", session.getUsername());
                sessionRepository.delete(session);
                return null;
            }

            if (recheckPassed) {
                session.setLastRecheckAt(now);
            }
        }

        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(rawCred.getBytes(StandardCharsets.UTF_8));

        if (Duration.between(session.getLastSeenAt(), now).toSeconds() > 60) {
            session.setLastSeenAt(now);
            session.setExpiresAt(now.plus(authProperties.getSessionTtl()));
            sessionRepository.save(session);
        }

        return new SessionInfo(session.getUsername(), basicAuth, session.isAdmin());
    }

    private SessionInfo resolveLdapSession(NotebookAuthSession session, Instant now) {
        String username = session.getUsername();
        if (Duration.between(session.getLastRecheckAt(), now).compareTo(authProperties.getRecheck()) > 0) {
            try {
                LdapAuthenticator.LdapUser ldapUser = ldapAuthenticator.lookupUser(username);
                session.setAdmin(ldapUser.isAdmin() || admins.contains(username));
                session.setLastRecheckAt(now);
                sessionRepository.save(session);
            } catch (NotebookApiException ex) {
                if (!"AUTH_BACKEND_UNAVAILABLE".equals(ex.getCode())) {
                    log.info("LDAP user {} no longer valid, invalidating session", username);
                    sessionRepository.delete(session);
                    return null;
                }
            }
        }
        if (Duration.between(session.getLastSeenAt(), now).toSeconds() > 60) {
            session.setLastSeenAt(now);
            session.setExpiresAt(now.plus(authProperties.getSessionTtl()));
            sessionRepository.save(session);
        }
        return new SessionInfo(username, LdapServiceAccount.basicHeader(authProperties), session.isAdmin());
    }

    private static boolean isLdap(String authMode) {
        return "ldap".equalsIgnoreCase(authMode);
    }

    private static boolean isOidc(String authMode) {
        return "oidc".equalsIgnoreCase(authMode);
    }

    record OidcTokens(String accessToken, String refreshToken, String idToken, long expiresAtMs) {}

    public NotebookAuthSession createOidcSession(String username, OidcTokenClient.Tokens tokens) {
        requireCrypto();
        Instant now = Instant.now();
        OidcTokens stored = new OidcTokens(tokens.accessToken(), tokens.refreshToken(), tokens.idToken(),
                now.toEpochMilli() + tokens.expiresInSeconds() * 1000L);
        NotebookAuthSession session = NotebookAuthSession.builder()
                .id(newSessionId())
                .username(username)
                .authMode("oidc")
                .credentialEnc(encryptTokens(stored))
                .isAdmin(admins.contains(username))
                .createdAt(now)
                .lastSeenAt(now)
                .lastRecheckAt(now)
                .expiresAt(now.plus(authProperties.getOidc().getSessionTimeout()))
                .build();
        return sessionRepository.save(session);
    }

    public Optional<String> oidcIdToken(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || credentialCrypto == null) {
            return Optional.empty();
        }
        return sessionRepository.findById(sessionId)
                .filter(s -> isOidc(s.getAuthMode()) && s.getCredentialEnc() != null)
                .flatMap(s -> {
                    try {
                        return Optional.ofNullable(decryptTokens(s.getCredentialEnc()).idToken());
                    } catch (Exception e) {
                        return Optional.empty();
                    }
                });
    }

    private SessionInfo resolveOidcSession(NotebookAuthSession session, Instant now) {
        if (credentialCrypto == null || session.getCredentialEnc() == null) {
            sessionRepository.delete(session);
            return null;
        }
        OidcTokens tokens;
        try {
            tokens = decryptTokens(session.getCredentialEnc());
        } catch (Exception e) {
            sessionRepository.delete(session);
            return null;
        }

        if (needsRefresh(tokens, now)) {
            NotebookAuthSession locked = sessionRepository.findByIdForUpdate(session.getId()).orElse(null);
            if (locked == null) {
                return null;
            }
            session = locked;
            try {
                tokens = decryptTokens(session.getCredentialEnc());
            } catch (Exception e) {
                sessionRepository.delete(session);
                return null;
            }
            if (needsRefresh(tokens, now)) {
                tokens = refreshOidcTokens(session, tokens, now);
                if (tokens == null) {
                    return null;
                }
            }
        }

        if (Duration.between(session.getLastSeenAt(), now).toSeconds() > 60) {
            session.setLastSeenAt(now);
            session.setExpiresAt(now.plus(authProperties.getOidc().getSessionTimeout()));
            sessionRepository.save(session);
        }
        return new SessionInfo(session.getUsername(), "Bearer " + tokens.accessToken(), session.isAdmin());
    }

    private OidcTokens refreshOidcTokens(NotebookAuthSession session, OidcTokens current, Instant now) {
        if (current.refreshToken() == null || current.refreshToken().isBlank()) {
            log.info("OIDC session for {} has no refresh token, invalidating", session.getUsername());
            sessionRepository.delete(session);
            return null;
        }
        OidcTokenClient.Tokens fresh;
        try {
            fresh = oidcTokenClient.refresh(current.refreshToken());
        } catch (OidcTokenClient.TokenEndpointException ex) {
            if ("invalid_grant".equals(ex.error()) || "invalid_client".equals(ex.error()) || "unauthorized_client".equals(ex.error())) {
                log.info("OIDC refresh for {} rejected ({}), invalidating session", session.getUsername(), ex.error());
                sessionRepository.delete(session);
                return null;
            }
            if (current.expiresAtMs() > now.toEpochMilli()) {
                log.warn("OIDC refresh for {} failed ({}), keeping current access token", session.getUsername(), ex.error());
                return current;
            }
            throw new NotebookApiException("AUTH_BACKEND_UNAVAILABLE", "Identity provider is unavailable", org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        }

        String username = oidcTokenVerifiers.accessToken().verifyAndGetUsername(fresh.accessToken());
        if (!username.equals(session.getUsername())) {
            log.warn("OIDC refresh returned a token for a different user, invalidating session");
            sessionRepository.delete(session);
            return null;
        }

        OidcTokens renewed = new OidcTokens(
                fresh.accessToken(),
                fresh.refreshToken() != null ? fresh.refreshToken() : current.refreshToken(),
                fresh.idToken() != null ? fresh.idToken() : current.idToken(),
                now.toEpochMilli() + fresh.expiresInSeconds() * 1000L);
        session.setCredentialEnc(encryptTokens(renewed));
        session.setLastRecheckAt(now);
        sessionRepository.save(session);
        return renewed;
    }

    private static boolean needsRefresh(OidcTokens tokens, Instant now) {
        return tokens.expiresAtMs() - now.toEpochMilli() < OIDC_REFRESH_SKEW_MS;
    }

    private void requireCrypto() {
        if (credentialCrypto == null) {
            throw new IllegalStateException("Encryption key is not configured for session storage");
        }
    }

    private byte[] encryptTokens(OidcTokens tokens) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
            node.put("a", tokens.accessToken());
            node.put("r", tokens.refreshToken());
            node.put("i", tokens.idToken());
            node.put("e", tokens.expiresAtMs());
            return credentialCrypto.encrypt(objectMapper.writeValueAsString(node));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize OIDC tokens", e);
        }
    }

    private OidcTokens decryptTokens(byte[] enc) throws java.io.IOException {
        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(credentialCrypto.decrypt(enc));
        return new OidcTokens(
                node.path("a").asText(null),
                node.hasNonNull("r") ? node.get("r").asText() : null,
                node.hasNonNull("i") ? node.get("i").asText() : null,
                node.path("e").asLong(0));
    }

    private static String newSessionId() {
        byte[] buf = new byte[32];
        new java.security.SecureRandom().nextBytes(buf);
        return java.util.HexFormat.of().formatHex(buf);
    }

    public void deleteSession(String sessionId) {
        if (sessionId != null) {
            sessionRepository.deleteById(sessionId);
        }
    }

    @Scheduled(fixedDelayString = "${notebook.auth.cleanup-interval:600000}")
    public void cleanupExpiredSessions() {
        sessionRepository.deleteExpiredSessions(Instant.now());
    }
}
