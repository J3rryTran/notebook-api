package com.vtnexus.notebook.execution.repository;

import com.vtnexus.notebook.execution.model.NotebookKyuubiSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NotebookKyuubiSessionRepository extends JpaRepository<NotebookKyuubiSession, String> {
    Optional<NotebookKyuubiSession> findByNotebookIdAndUserIdAndStatusNot(String notebookId, String userId, String status);
    Optional<NotebookKyuubiSession> findByIdAndUserId(String id, String userId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT s FROM NotebookKyuubiSession s WHERE s.id = :id")
    Optional<NotebookKyuubiSession> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") String id);

    Optional<NotebookKyuubiSession> findFirstByNotebookIdAndUserIdAndStatusInOrderByCreatedAtDesc(String notebookId, String userId, java.util.Collection<String> statuses);

    long countByUserIdAndStatusIn(String userId, java.util.Collection<String> statuses);
    java.util.List<NotebookKyuubiSession> findByStatusInAndLastUsedAtBefore(java.util.Collection<String> statuses, java.time.Instant cutoff);
}
