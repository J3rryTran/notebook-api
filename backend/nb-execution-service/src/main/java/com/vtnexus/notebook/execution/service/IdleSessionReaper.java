package com.vtnexus.notebook.execution.service;

import com.vtnexus.notebook.common.config.KyuubiProperties;
import com.vtnexus.notebook.execution.client.KyuubiRestClient;
import com.vtnexus.notebook.execution.model.NotebookKyuubiSession;
import com.vtnexus.notebook.execution.repository.NotebookCellRunRepository;
import com.vtnexus.notebook.execution.repository.NotebookKyuubiSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class IdleSessionReaper {

    private static final Logger log = LoggerFactory.getLogger(IdleSessionReaper.class);
    private static final List<String> OPEN_STATUSES = List.of("READY", "BUSY");

    private final NotebookKyuubiSessionRepository sessionRepository;
    private final NotebookCellRunRepository cellRunRepository;
    private final KyuubiRestClient kyuubiRestClient;
    private final ServiceAccountTokenProvider serviceAccountTokenProvider;
    private final TransactionTemplate transactionTemplate;
    private final Duration idleTimeout;

    public IdleSessionReaper(NotebookKyuubiSessionRepository sessionRepository,
                             NotebookCellRunRepository cellRunRepository,
                             KyuubiRestClient kyuubiRestClient,
                             ServiceAccountTokenProvider serviceAccountTokenProvider,
                             TransactionTemplate transactionTemplate,
                             KyuubiProperties kyuubiProperties) {
        this.sessionRepository = sessionRepository;
        this.cellRunRepository = cellRunRepository;
        this.kyuubiRestClient = kyuubiRestClient;
        this.serviceAccountTokenProvider = serviceAccountTokenProvider;
        this.transactionTemplate = transactionTemplate;
        this.idleTimeout = kyuubiProperties.getIdleSessionTimeout();
    }

    @Scheduled(fixedDelayString = "${kyuubi.idle-reaper-interval:300000}", initialDelayString = "${kyuubi.idle-reaper-interval:300000}")
    public void closeIdleSessions() {
        if (idleTimeout == null || idleTimeout.isZero() || idleTimeout.isNegative()) {
            return;
        }
        Instant cutoff = Instant.now().minus(idleTimeout);
        for (NotebookKyuubiSession candidate : sessionRepository.findByStatusInAndLastUsedAtBefore(OPEN_STATUSES, cutoff)) {
            try {
                closeIfStillIdle(candidate.getId(), cutoff);
            } catch (Exception ex) {
                log.warn("Failed to close idle session {}: {}", candidate.getId(), ex.getMessage());
            }
        }
    }

    void closeIfStillIdle(String sessionId, Instant cutoff) {
        if (!cellRunRepository.findRunningBySessionId(sessionId).isEmpty()) {
            return;
        }
        NotebookKyuubiSession closed = transactionTemplate.execute(status -> {
            NotebookKyuubiSession s = sessionRepository.findByIdForUpdate(sessionId).orElse(null);
            if (s == null || !OPEN_STATUSES.contains(s.getStatus())
                    || s.getLastUsedAt() == null || !s.getLastUsedAt().isBefore(cutoff)) {
                return null;
            }
            s.setStatus("CLOSED");
            return sessionRepository.save(s);
        });
        if (closed == null) {
            return;
        }
        if (Boolean.TRUE.equals(closed.getProxied()) && closed.getKyuubiSessionId() != null) {
            try {
                kyuubiRestClient.closeSession(closed.getKyuubiSessionId(), closed.getKyuubiInstance(),
                        "Bearer " + serviceAccountTokenProvider.getServiceAccountToken());
            } catch (Exception ex) {
                log.warn("Idle session {} marked CLOSED but Kyuubi close failed: {}", sessionId, ex.getMessage());
            }
        }
        log.info("Closed idle notebook session {} (user={}, idle > {})", sessionId, closed.getUserId(), idleTimeout);
    }
}
