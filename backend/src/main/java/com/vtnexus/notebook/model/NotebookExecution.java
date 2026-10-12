package com.vtnexus.notebook.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "notebook_executions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotebookExecution {

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @Column(name = "notebook_id", length = 64, nullable = false)
    private String notebookId;

    @Column(name = "cell_id", length = 64, nullable = false)
    private String cellId;

    @Column(name = "kyuubi_operation_id", length = 64)
    private String kyuubiOperationId;

    @Column(name = "execution_count")
    private Integer executionCount;

    @Column(length = 32, nullable = false)
    @Builder.Default
    private String status = "PENDING"; // PENDING, RUNNING, FINISHED, ERROR, CANCELLED

    @Column(columnDefinition = "TEXT")
    private String statement;

    @Column(length = 32)
    private String language;

    @Column(name = "submitted_by", length = 128, nullable = false)
    private String submittedBy;

    @Column(name = "sequence_number", nullable = false)
    @Builder.Default
    private Long sequenceNumber = 0L;

    @Column(name = "log_output", columnDefinition = "TEXT")
    private String logOutput;

    @Column(name = "result_output", columnDefinition = "TEXT")
    private String resultOutput; // JSON MIME bundle or rowset

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
