package com.vtnexus.notebook.store;

import com.vtnexus.notebook.store.model.Notebook;
import com.vtnexus.notebook.store.repository.NotebookRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = NotebookServiceApplication.class)
class MariaDbMigrationIT {

    static MariaDBContainer<?> mariaDB = new MariaDBContainer<>("mariadb:10.11")
            .withDatabaseName("test")
            .withUsername("testuser")
            .withPassword("testpass")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_unicode_ci",
                    "--max-allowed-packet=64M"
            );

    static {
        mariaDB.start();
        System.setProperty("spring.datasource.url", mariaDB.getJdbcUrl());
        System.setProperty("spring.datasource.username", mariaDB.getUsername());
        System.setProperty("spring.datasource.password", mariaDB.getPassword());
        System.setProperty("notebook.auth.mode", "oidc");
        System.setProperty("notebook.internalToken", "secret-token-it");
        System.setProperty("notebook.internal-token", "secret-token-it");
        System.setProperty("notebook.encryption-key", "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=");
        System.setProperty("notebook.auth.oidc.jwt.issuer", "https://keycloak.it.invalid/realms/it");
        System.setProperty("notebook.auth.oidc.jwt.audience", "kyuubi");
        System.setProperty("notebook.auth.oidc.jwt.jwks-url", "http://keycloak.it.invalid/certs");
        System.setProperty("notebook.auth.oidc.ui.client-secret", "it-secret");
        System.setProperty("notebook.auth.oidc.authorization-endpoint", "https://keycloak.it.invalid/auth");
        System.setProperty("notebook.auth.oidc.token-endpoint", "http://keycloak.it.invalid/token");
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mariaDB::getJdbcUrl);
        registry.add("spring.datasource.username", mariaDB::getUsername);
        registry.add("spring.datasource.password", mariaDB::getPassword);
        registry.add("notebook.auth.mode", () -> "oidc");
        registry.add("notebook.internalToken", () -> "secret-token-it");
        registry.add("notebook.internal-token", () -> "secret-token-it");
    }

    @Autowired
    private NotebookRepository notebookRepository;

    @Test
    @DisplayName("T1 (a): Flyway V1->V3 chạy trên MariaDB trống, hibernate ddl-auto: validate thành công")
    void testMigrationOnEmptyDbAndHibernateValidate() {
        assertThat(notebookRepository).isNotNull();
    }

    @Test
    @DisplayName("T2: Lưu document_json 5 MB, emoji sau V3, và xung đột lock_version (Optimistic Locking)")
    void testDocumentLargeSizeEmojiAndOptimisticLocking() {
        String id = UUID.randomUUID().toString();
        StringBuilder largeContent = new StringBuilder("{\"cells\":[{\"source\":\"");
        largeContent.append("🚀 Hello MariaDB utf8mb4! Xin chào tiếng Việt có dấu. 🎉 ");
        String chunk = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*()_+-=[]{}|;':,./<>?~`";
        int targetSize = 5 * 1024 * 1024;
        while (largeContent.length() < targetSize) {
            largeContent.append(chunk);
        }
        largeContent.append("\"}]}");
        String docJson = largeContent.toString();

        Notebook notebook = Notebook.builder()
                .id(id)
                .name("Notebook Test Large & Emoji 🚀")
                .owner("tester")
                .notebookFormat("SQLLAB_JSON_V1")
                .defaultLanguage("SQL")
                .documentJson(docJson)
                .currentRevision(1L)
                .lockVersion(0L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        Notebook saved = notebookRepository.save(notebook);
        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getLockVersion()).isEqualTo(0L);

        Notebook loaded = notebookRepository.findById(id).orElseThrow();
        assertThat(loaded.getDocumentJson()).isEqualTo(docJson);
        assertThat(loaded.getName()).contains("🚀");

        Notebook concurrentCopy = Notebook.builder()
                .id(saved.getId())
                .nodeId(saved.getNodeId())
                .name("Concurrent Update")
                .owner(saved.getOwner())
                .notebookFormat(saved.getNotebookFormat())
                .defaultLanguage(saved.getDefaultLanguage())
                .documentJson("{\"modified\":1}")
                .currentRevision(saved.getCurrentRevision())
                .lockVersion(saved.getLockVersion())
                .createdAt(saved.getCreatedAt())
                .updatedAt(Instant.now())
                .build();

        loaded.setName("Updated First");
        notebookRepository.save(loaded);

        assertThatThrownBy(() -> notebookRepository.save(concurrentCopy))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}
