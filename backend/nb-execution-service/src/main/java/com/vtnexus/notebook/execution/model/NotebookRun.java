package com.vtnexus.notebook.execution.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "notebook_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotebookRun {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "notebook_id", length = 36, nullable = false)
    private String notebookId;

    @Column(name = "run_type", length = 30, nullable = false)
    @Builder.Default
    private String runType = "CELL";

    @Column(name = "status", length = 30, nullable = false)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "triggered_by", length = 255, nullable = false)
    private String triggeredBy;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "kyuubi_notebook_id", length = 64)
    private String kyuubiNotebookId;

    @Column(name = "cancel_requested", nullable = false)
    @Builder.Default
    private boolean cancelRequested = false;

    @Column(name = "cells_total", nullable = false)
    @Builder.Default
    private int cellsTotal = 0;

    @Column(name = "cells_done", nullable = false)
    @Builder.Default
    private int cellsDone = 0;

    @Column(name = "current_cell_id", length = 128)
    private String currentCellId;

    @Column(name = "current_execution_id", length = 128)
    private String currentExecutionId;

    @Column(name = "notebook_revision")
    private Long notebookRevision;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "notebook_saved_at")
    private Instant notebookSavedAt;

    @Column(name = "kyuubi_session_ref", length = 36)
    private String kyuubiSessionRef;
}
