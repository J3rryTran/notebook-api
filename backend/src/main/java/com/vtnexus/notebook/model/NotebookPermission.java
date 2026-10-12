package com.vtnexus.notebook.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "notebook_permissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotebookPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notebook_id", length = 64, nullable = false)
    private String notebookId;

    @Column(name = "principal_type", length = 32, nullable = false)
    private String principalType; // USER

    @Column(name = "principal_id", length = 128, nullable = false)
    private String principalId;

    @Column(length = 32, nullable = false)
    private String role; // OWNER, EDITOR, RUNNER, VIEWER

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
