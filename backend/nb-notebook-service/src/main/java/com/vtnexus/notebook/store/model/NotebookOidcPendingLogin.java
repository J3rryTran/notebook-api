package com.vtnexus.notebook.store.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "notebook_oidc_pending_logins")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotebookOidcPendingLogin {

    @Id
    @Column(name = "state", length = 64, nullable = false)
    private String state;

    @Column(name = "code_verifier", length = 128, nullable = false)
    private String codeVerifier;

    @Column(name = "nonce", length = 64, nullable = false)
    private String nonce;

    @Column(name = "redirect_path", length = 1024, nullable = false)
    private String redirectPath;

    @Column(name = "redirect_uri", length = 1024, nullable = false)
    private String redirectUri;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
