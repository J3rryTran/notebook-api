package com.vtnexus.notebook.execution.repository;

import com.vtnexus.notebook.execution.model.NotebookCellRun;
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
public interface NotebookCellRunRepository extends JpaRepository<NotebookCellRun, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM NotebookCellRun c WHERE c.id = :id")
    Optional<NotebookCellRun> findByIdForUpdate(@Param("id") String id);

    List<NotebookCellRun> findByNotebookRunIdOrderByExecutionOrderAsc(String notebookRunId);

    @Query("SELECT c FROM NotebookCellRun c JOIN NotebookRun r ON c.notebookRunId = r.id WHERE r.notebookId = :notebookId AND c.cellId = :cellId AND r.triggeredBy = :user ORDER BY c.startedAt DESC")
    List<NotebookCellRun> findLatestByNotebookAndCellAndUser(@Param("notebookId") String notebookId, @Param("cellId") String cellId, @Param("user") String user, Pageable pageable);
}
