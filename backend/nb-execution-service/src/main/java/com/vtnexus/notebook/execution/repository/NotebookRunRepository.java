package com.vtnexus.notebook.execution.repository;

import com.vtnexus.notebook.execution.model.NotebookRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NotebookRunRepository extends JpaRepository<NotebookRun, String> {
    Optional<NotebookRun> findByIdAndTriggeredBy(String id, String triggeredBy);
}
