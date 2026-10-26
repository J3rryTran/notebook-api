package com.vtnexus.notebook.store.repository;

import com.vtnexus.notebook.store.model.NotebookAuthSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface NotebookAuthSessionRepository extends JpaRepository<NotebookAuthSession, String> {
    List<NotebookAuthSession> findByUsername(String username);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM NotebookAuthSession s WHERE s.id = :id")
    java.util.Optional<NotebookAuthSession> findByIdForUpdate(@Param("id") String id);

    @Modifying
    @Query("DELETE FROM NotebookAuthSession s WHERE s.expiresAt < :now")
    int deleteExpiredSessions(@Param("now") Instant now);
}
