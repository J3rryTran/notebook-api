package com.vtnexus.notebook.store.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "notebook_folders", schema = "nb_document")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotebookFolder {

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(name = "parent_id", length = 64)
    private String parentId;

    @Column(nullable = false, length = 128)
    private String owner;

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

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
