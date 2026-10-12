package com.vtnexus.notebook.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "notebooks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notebook {

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(name = "folder_id", length = 64)
    private String folderId;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "default_catalog", length = 128)
    private String defaultCatalog;

    @Column(name = "default_schema", length = 128)
    private String defaultSchema;

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

    @OneToMany(mappedBy = "notebook", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position ASC")
    @Builder.Default
    private List<NotebookCell> cells = new ArrayList<>();
}
