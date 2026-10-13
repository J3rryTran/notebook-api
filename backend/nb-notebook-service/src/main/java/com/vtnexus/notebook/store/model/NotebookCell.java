package com.vtnexus.notebook.store.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "notebook_cells", schema = "nb_document")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotebookCell {

    @Id
    @Column(length = 64, nullable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notebook_id", nullable = false)
    @JsonIgnore
    private Notebook notebook;

    @Column(name = "cell_type", length = 32, nullable = false)
    private String cellType; // CODE, MARKDOWN, RAW

    @Column(length = 32, nullable = false)
    private String language; // SQL, PYTHON, MARKDOWN

    @Column(columnDefinition = "TEXT", nullable = false)
    private String source;

    @Column(nullable = false)
    private Integer position;

    @Column(name = "execution_count")
    private Integer executionCount;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
