package com.vtnexus.notebook.store.model;

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

    @Column(name = "notebook_id", length = 36, nullable = false)
    private String notebookId;

    @Column(name = "principal_type", length = 32, nullable = false)
    @Builder.Default
    private String principalType = "USER";

    @Column(name = "principal_id", length = 255, nullable = false)
    private String principalId;

    @Column(name = "role", length = 32, nullable = false)
    private String role;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
