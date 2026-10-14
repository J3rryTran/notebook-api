package com.vtnexus.notebook.execution.repository;

import com.vtnexus.notebook.execution.model.NotebookKyuubiSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NotebookKyuubiSessionRepository extends JpaRepository<NotebookKyuubiSession, String> {
    Optional<NotebookKyuubiSession> findByNotebookIdAndUserIdAndStatusNot(String notebookId, String userId, String status);
    Optional<NotebookKyuubiSession> findByIdAndUserId(String id, String userId);
}
