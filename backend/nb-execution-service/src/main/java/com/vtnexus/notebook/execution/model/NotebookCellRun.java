package com.vtnexus.notebook.execution.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "notebook_cell_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotebookCellRun {

    @Id
    @Column(name = "id", columnDefinition = "CHAR(36)", length = 36, nullable = false)
    private String id;

    @Column(name = "notebook_run_id", columnDefinition = "CHAR(36)", length = 36, nullable = false)
    private String notebookRunId;

    @Column(name = "cell_id", length = 100, nullable = false)
    private String cellId;

    @Column(name = "execution_order", nullable = false)
    @Builder.Default
    private int executionOrder = 0;

    @Column(name = "language", length = 30, nullable = false)
    @Builder.Default
    private String language = "SQL";

    @Column(name = "status", length = 30, nullable = false)
    @Builder.Default
    private String status = "RUNNING";

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "execution_time_ms")
    private Long executionTimeMs;

    @Column(name = "output_type", length = 30)
    private String outputType;

    @Column(name = "output_json", columnDefinition = "LONGTEXT")
    private String outputJson;

    @Column(name = "output_storage_uri", length = 2048)
    private String outputStorageUri;

    @Column(name = "error_message", columnDefinition = "LONGTEXT")
    private String errorMessage;

    @Column(name = "execution_id", length = 128)
    private String executionId;

    @Column(name = "source", columnDefinition = "LONGTEXT")
    private String source;

    @Column(name = "execution_count")
    private Integer executionCount;
}
