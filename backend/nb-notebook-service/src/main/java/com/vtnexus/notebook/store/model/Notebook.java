package com.vtnexus.notebook.store.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "notebooks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notebook {

    @Id
    @Column(name = "id", columnDefinition = "CHAR(36)", length = 36, nullable = false)
    private String id;

    @Column(name = "node_id", columnDefinition = "CHAR(36)", length = 36)
    private String nodeId;

    @Column(name = "notebook_format", length = 30, nullable = false)
    @Builder.Default
    private String notebookFormat = "SQLLAB_JSON_V1";

    @Column(name = "default_language", length = 30, nullable = false)
    @Builder.Default
    private String defaultLanguage = "SQL";

    @Column(name = "document_json", columnDefinition = "LONGTEXT", nullable = false)
    private String documentJson;

    @Column(name = "current_revision", nullable = false)
    @Builder.Default
    private Long currentRevision = 1L;

    @Version
    @Column(name = "lock_version", nullable = false)
    @Builder.Default
    private Long lockVersion = 0L;

    @Column(name = "name", length = 255, nullable = false)
    @Builder.Default
    private String name = "";

    @Column(name = "owner", length = 255, nullable = false)
    @Builder.Default
    private String owner = "";

    @Column(name = "folder_id", columnDefinition = "CHAR(36)", length = 36)
    private String folderId;

    @Column(name = "description", columnDefinition = "LONGTEXT")
    private String description;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "zeppelin_note_id", length = 64)
    private String zeppelinNoteId;

    @Column(name = "kyuubi_notebook_id", length = 64)
    private String kyuubiNotebookId;
}
