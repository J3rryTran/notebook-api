package com.vtnexus.notebook.execution.repository;

import com.vtnexus.notebook.execution.model.NotebookExecutionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotebookExecutionLogRepository extends JpaRepository<NotebookExecutionLog, Long> {
    List<NotebookExecutionLog> findByCellRunIdOrderBySequenceAsc(String cellRunId);
    List<NotebookExecutionLog> findByCellRunIdAndSequenceGreaterThanOrderBySequenceAsc(String cellRunId, Long sequence);

    @org.springframework.data.jpa.repository.Query("SELECT MAX(l.sequence) FROM NotebookExecutionLog l WHERE l.cellRunId = :cellRunId")
    Long findMaxSequenceByCellRunId(@org.springframework.data.repository.query.Param("cellRunId") String cellRunId);
}

