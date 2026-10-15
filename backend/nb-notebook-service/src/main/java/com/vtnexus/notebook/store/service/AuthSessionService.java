package com.vtnexus.notebook.store.service;

import com.vtnexus.notebook.common.auth.AuthProperties;
import com.vtnexus.notebook.common.auth.CredentialCrypto;
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

    public AuthSessionService(
            NotebookAuthSessionRepository sessionRepository,
            AuthProperties authProperties,
            LdapAuthenticator ldapAuthenticator,
            com.vtnexus.notebook.common.config.NotebookProperties notebookProperties,
            com.vtnexus.notebook.common.config.KyuubiProperties kyuubiProperties) {
        this.sessionRepository = sessionRepository;
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
        if (credentialCrypto == null) {
            throw new IllegalStateException("Encryption key is not configured for session storage");
        }

        String rawCred = username + ":" + password;
        byte[] enc = credentialCrypto.encrypt(rawCred);
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
        if (sessionId == null || sessionId.isBlank() || credentialCrypto == null) {
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

        String rawCred;
        try {
            rawCred = credentialCrypto.decrypt(session.getCredentialEnc());
        } catch (Exception e) {
            sessionRepository.delete(session);
            return null;
        }

        int colonIdx = rawCred.indexOf(':');
        String username = colonIdx > 0 ? rawCred.substring(0, colonIdx) : session.getUsername();
        String password = colonIdx > 0 ? rawCred.substring(colonIdx + 1) : "";

        // C17 (A8): now - last_recheck_at > recheck -> recheck với backend
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
            } else if ("ldap".equalsIgnoreCase(session.getAuthMode())) {
                try {
                    LdapAuthenticator.LdapUser ldapUser = ldapAuthenticator.authenticate(username, password);
                    recheckPassed = true;
                    session.setAdmin(ldapUser.isAdmin() || admins.contains(username));
                } catch (NotebookApiException ex) {
                    if ("AUTH_BACKEND_UNAVAILABLE".equals(ex.getCode())) {
                        backendUnavailable = true;
                    } else {
                        recheckPassed = false;
                    }
                } catch (Exception ex) {
                    log.warn("LDAP unavailable during session recheck: {}", ex.getMessage());
                    backendUnavailable = true;
                }
            } else {
                recheckPassed = true;
            }

            if (!backendUnavailable && !recheckPassed) {
                // Xác thực thất bại -> Xóa phiên, trả null (401)
                log.info("Session recheck failed for user {}, invalidating session", session.getUsername());
                sessionRepository.delete(session);
                return null;
            }

            if (recheckPassed) {
                session.setLastRecheckAt(now);
            }
        }

        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(rawCred.getBytes(StandardCharsets.UTF_8));

        // Cập nhật lastSeenAt và trượt hạn phiên tối đa 1 lần/phút
        if (Duration.between(session.getLastSeenAt(), now).toSeconds() > 60) {
            session.setLastSeenAt(now);
            session.setExpiresAt(now.plus(authProperties.getSessionTtl()));
            sessionRepository.save(session);
        }

        return new SessionInfo(session.getUsername(), basicAuth, session.isAdmin());
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
