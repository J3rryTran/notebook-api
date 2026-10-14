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

    @Modifying
    @Query("DELETE FROM NotebookAuthSession s WHERE s.expiresAt < :now")
    int deleteExpiredSessions(@Param("now") Instant now);
}
