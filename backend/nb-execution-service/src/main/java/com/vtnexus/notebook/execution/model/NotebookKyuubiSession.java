package com.vtnexus.notebook.execution.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "notebook_kyuubi_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotebookKyuubiSession {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "notebook_id", length = 36, nullable = false)
    private String notebookId;

    @Column(name = "user_id", length = 255, nullable = false)
    private String userId;

    @Column(name = "kyuubi_session_id", length = 64, nullable = false)
    private String kyuubiSessionId;

    @Column(name = "kyuubi_instance", length = 256)
    private String kyuubiInstance;

    @Column(name = "status", length = 32, nullable = false)
    @Builder.Default
    private String status = "INITIALIZING";

    @Column(name = "execution_count_seq", nullable = false)
    @Builder.Default
    private Integer executionCountSeq = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_used_at", nullable = false)
    @Builder.Default
    private Instant lastUsedAt = Instant.now();
}
