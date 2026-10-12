package com.vtnexus.notebook.model;

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
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notebook_id", length = 64, nullable = false)
    private String notebookId;

    @Column(name = "revision_number", nullable = false)
    private Long revisionNumber;

    @Column(name = "reason")
    private String reason;

    @Column(name = "is_protected", nullable = false)
    @Builder.Default
    private Boolean isProtected = false;

    @Column(name = "document_json", columnDefinition = "TEXT", nullable = false)
    private String documentJson;

    @Column(name = "created_by", length = 128, nullable = false)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
