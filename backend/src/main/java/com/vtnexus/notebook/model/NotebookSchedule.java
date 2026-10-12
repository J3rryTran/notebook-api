package com.vtnexus.notebook.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "notebook_schedules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotebookSchedule {

    @Id
    @Column(name = "notebook_id", length = 64, nullable = false)
    private String notebookId;

    @Column(name = "cron_expression", nullable = false)
    private String cronExpression;

    @Column(nullable = false, length = 64)
    private String timezone;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "failure_policy", length = 32, nullable = false)
    @Builder.Default
    private String failurePolicy = "STOP_ON_ERROR"; // STOP_ON_ERROR, CONTINUE

    @Column(name = "overlap_policy", length = 32, nullable = false)
    @Builder.Default
    private String overlapPolicy = "SKIP_IF_RUNNING"; // SKIP_IF_RUNNING, CANCEL_RUNNING, RUN_CONCURRENT

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 1L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
