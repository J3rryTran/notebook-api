package com.vtnexus.notebook.store.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "app_users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppUser {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "username", length = 255, nullable = false)
    private String username;

    @Column(name = "display_name", length = 255, nullable = false)
    private String displayName;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "ranger_username", length = 255, nullable = false)
    private String rangerUsername;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
