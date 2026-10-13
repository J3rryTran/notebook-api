package com.vtnexus.notebook.execution.repository;

import com.vtnexus.notebook.execution.model.NotebookSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotebookSessionRepository extends JpaRepository<NotebookSession, String> {
    List<NotebookSession> findByNotebookId(String notebookId);
    Optional<NotebookSession> findByNotebookIdAndUserIdAndStatusNot(String notebookId, String userId, String status);
    long countByStatus(String status);
}
