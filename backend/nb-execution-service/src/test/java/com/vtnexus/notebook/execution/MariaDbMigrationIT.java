package com.vtnexus.notebook.execution;

import com.vtnexus.notebook.execution.repository.NotebookCellRunRepository;
import com.vtnexus.notebook.execution.repository.NotebookExecutionLogRepository;
import com.vtnexus.notebook.execution.repository.NotebookKyuubiSessionRepository;
import com.vtnexus.notebook.execution.repository.NotebookRunRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ExecutionServiceApplication.class)
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

        Flyway flyway = Flyway.configure()
                .dataSource(mariaDB.getJdbcUrl(), mariaDB.getUsername(), mariaDB.getPassword())
                .locations("filesystem:../nb-notebook-service/src/main/resources/db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
        flyway.migrate();

        System.setProperty("spring.datasource.url", mariaDB.getJdbcUrl());
        System.setProperty("spring.datasource.username", mariaDB.getUsername());
        System.setProperty("spring.datasource.password", mariaDB.getPassword());
        System.setProperty("notebook.auth.mode", "oidc");
        System.setProperty("notebook.internalToken", "secret-token-it");
        System.setProperty("notebook.internal-token", "secret-token-it");
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mariaDB::getJdbcUrl);
        registry.add("spring.datasource.username", mariaDB::getUsername);
        registry.add("spring.datasource.password", mariaDB::getPassword);
        registry.add("kyuubi.rest-url", () -> "http://kyuubi.invalid:10099");
        registry.add("notebook.auth.mode", () -> "oidc");
        registry.add("notebook.internalToken", () -> "secret-token-it");
        registry.add("notebook.internal-token", () -> "secret-token-it");
    }

    @Autowired
    private NotebookCellRunRepository cellRunRepository;

    @Autowired
    private NotebookRunRepository runRepository;

    @Autowired
    private NotebookExecutionLogRepository logRepository;

    @Autowired
    private NotebookKyuubiSessionRepository sessionRepository;

    @Test
    @DisplayName("T1 (Execution-Service): Hibernate ddl-auto: validate thành công với schema MariaDB 10.11")
    void testExecutionServiceHibernateValidateOnMariaDb() {
        assertThat(cellRunRepository).isNotNull();
        assertThat(runRepository).isNotNull();
        assertThat(logRepository).isNotNull();
        assertThat(sessionRepository).isNotNull();
    }
}
