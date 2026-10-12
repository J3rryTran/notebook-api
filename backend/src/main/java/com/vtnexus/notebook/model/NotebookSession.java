package com.vtnexus.notebook.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "notebook_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotebookSession {

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(name = "notebook_id", length = 64, nullable = false)
    private String notebookId;

    @Column(name = "kyuubi_session_id", length = 64)
    private String kyuubiSessionId;

    @Column(name = "kyuubi_instance", length = 256)
    private String kyuubiInstance;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(length = 32, nullable = false)
    @Builder.Default
    private String status = "INITIALIZING"; // INITIALIZING, READY, BUSY, LOST, CLOSED

    @Column(name = "execution_count_seq", nullable = false)
    @Builder.Default
    private Integer executionCountSeq = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
