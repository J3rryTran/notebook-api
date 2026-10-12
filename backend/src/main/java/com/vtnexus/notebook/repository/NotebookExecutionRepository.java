package com.vtnexus.notebook.repository;

import com.vtnexus.notebook.model.NotebookExecution;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotebookExecutionRepository extends JpaRepository<NotebookExecution, String> {
    List<NotebookExecution> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);
    List<NotebookExecution> findByNotebookIdOrderByCreatedAtDesc(String notebookId, Pageable pageable);
    Optional<NotebookExecution> findByKyuubiOperationId(String kyuubiOperationId);
}
