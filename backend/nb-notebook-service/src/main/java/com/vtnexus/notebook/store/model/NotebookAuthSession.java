package com.vtnexus.notebook.store.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "notebook_auth_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotebookAuthSession {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "username", length = 255, nullable = false)
    private String username;

    @Column(name = "auth_mode", length = 16, nullable = false)
    private String authMode;

    @Column(name = "credential_enc", columnDefinition = "VARBINARY(1024)", nullable = false)
    private byte[] credentialEnc;

    @Column(name = "is_admin", nullable = false)
    @Builder.Default
    private boolean isAdmin = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "last_recheck_at", nullable = false)
    private Instant lastRecheckAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
