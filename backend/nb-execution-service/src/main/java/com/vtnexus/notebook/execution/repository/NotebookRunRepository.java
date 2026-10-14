package com.vtnexus.notebook.execution.repository;

import com.vtnexus.notebook.execution.model.NotebookRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NotebookRunRepository extends JpaRepository<NotebookRun, String> {
    Optional<NotebookRun> findByIdAndTriggeredBy(String id, String triggeredBy);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT r FROM NotebookRun r WHERE r.id = :id")
    Optional<NotebookRun> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") String id);
}

