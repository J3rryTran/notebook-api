package com.vtnexus.notebook.execution.repository;

import com.vtnexus.notebook.execution.model.NotebookExecution;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotebookExecutionRepository extends JpaRepository<NotebookExecution, String> {
    
    // Q5: Output của execution chỉ người chạy (submittedBy) đọc được
    Optional<NotebookExecution> findByIdAndSubmittedBy(String id, String submittedBy);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM NotebookExecution e WHERE e.id = :id")
    Optional<NotebookExecution> findByIdForUpdate(@Param("id") String id);

    List<NotebookExecution> findByNotebookIdAndCellIdAndSubmittedByOrderByCreatedAtDesc(
            String notebookId, String cellId, String submittedBy, Pageable pageable);

    long countByStatus(String status);
}
