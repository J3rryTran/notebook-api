package com.vtnexus.notebook.store.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "notebook_revisions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotebookRevision {

    @Id
    @Column(name = "id", columnDefinition = "CHAR(36)", length = 36, nullable = false)
    private String id;

    @Column(name = "notebook_id", columnDefinition = "CHAR(36)", length = 36, nullable = false)
    private String notebookId;

    @Column(name = "revision_number", nullable = false)
    private Long revisionNumber;

    @Column(name = "document_json", columnDefinition = "LONGTEXT", nullable = false)
    private String documentJson;

    @Column(name = "revision_type", length = 30, nullable = false)
    @Builder.Default
    private String revisionType = "AUTO";

    @Column(name = "created_by", length = 255, nullable = false)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
