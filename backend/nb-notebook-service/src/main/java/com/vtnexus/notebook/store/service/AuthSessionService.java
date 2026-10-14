package com.vtnexus.notebook.store.service;

import com.vtnexus.notebook.common.auth.CredentialCrypto;
import com.vtnexus.notebook.common.auth.SessionInfo;
import com.vtnexus.notebook.store.model.NotebookAuthSession;
import com.vtnexus.notebook.store.repository.NotebookAuthSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final NotebookAuthSessionRepository sessionRepository;
    private final CredentialCrypto credentialCrypto;
    private final Duration sessionTtl;
    private final List<String> admins;

    public AuthSessionService(
            NotebookAuthSessionRepository sessionRepository,
            @Value("${notebook.encryptionKey:}") String encryptionKey,
            @Value("${notebook.auth.sessionTtl:8h}") Duration sessionTtl,
            @Value("${notebook.admins:}") List<String> admins) {
        this.sessionRepository = sessionRepository;
        this.sessionTtl = sessionTtl;
        this.admins = (admins != null) ? admins : List.of();
        this.credentialCrypto = (encryptionKey != null && !encryptionKey.isBlank())
                ? new CredentialCrypto(encryptionKey)
                : null;
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
                .expiresAt(now.plus(sessionTtl))
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

        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(rawCred.getBytes(StandardCharsets.UTF_8));

        // Cập nhật lastSeenAt và trượt hạn phiên tối đa 1 lần/phút
        if (Duration.between(session.getLastSeenAt(), now).toSeconds() > 60) {
            session.setLastSeenAt(now);
            session.setExpiresAt(now.plus(sessionTtl));
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
