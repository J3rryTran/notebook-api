package com.vtnexus.notebook.execution.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "notebook_execution_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotebookExecutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cell_run_id", length = 36, nullable = false)
    private String cellRunId;

    @Column(name = "sequence", nullable = false)
    private Long sequence;

    @Column(name = "line", columnDefinition = "LONGTEXT", nullable = false)
    private String line;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
