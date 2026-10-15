package com.vtnexus.notebook.store;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.MariaDBContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class MariaDbBaselineMigrationIT {

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
    }

    @Test
    @DisplayName("T1 (b): DB có sẵn 5 bảng utf8mb3 theo DDL §1.1, Flyway baseline + V2..V5 giữ nguyên dữ liệu, Hibernate validate thành công")
    void testMigrationOnExistingBaselineDb() throws Exception {
        // 1. Tạo sẵn 5 bảng utf8mb3 đúng theo DDL §1.1 plan
        try (Connection conn = DriverManager.getConnection(mariaDB.getJdbcUrl(), mariaDB.getUsername(), mariaDB.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE notebooks (\n" +
                    "  id CHAR(36) NOT NULL, node_id CHAR(36) NOT NULL,\n" +
                    "  notebook_format VARCHAR(30) NOT NULL, default_language VARCHAR(30) NOT NULL,\n" +
                    "  document_json LONGTEXT NOT NULL,\n" +
                    "  current_revision BIGINT NOT NULL DEFAULT 1, lock_version BIGINT NOT NULL DEFAULT 0,\n" +
                    "  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,\n" +
                    "  zeppelin_note_id VARCHAR(64) NULL, kyuubi_notebook_id VARCHAR(64) NULL,\n" +
                    "  PRIMARY KEY (id), UNIQUE KEY uk_notebook_node (node_id), KEY idx_notebook_kyuubi_notebook (kyuubi_notebook_id)\n" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_general_ci;");

            stmt.execute("CREATE TABLE notebook_revisions (\n" +
                    "  id CHAR(36) NOT NULL, notebook_id CHAR(36) NOT NULL, revision_number BIGINT NOT NULL,\n" +
                    "  document_json LONGTEXT NOT NULL, revision_type VARCHAR(30) NOT NULL,\n" +
                    "  created_by VARCHAR(255) NOT NULL, created_at DATETIME(6) NOT NULL,\n" +
                    "  PRIMARY KEY (id), UNIQUE KEY uk_notebook_revision (notebook_id, revision_number),\n" +
                    "  KEY idx_notebook_revisions_created_at (created_at, notebook_id, revision_number)\n" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;");

            stmt.execute("CREATE TABLE notebook_runs (\n" +
                    "  id CHAR(36) NOT NULL, notebook_id CHAR(36) NOT NULL, run_type VARCHAR(30) NOT NULL,\n" +
                    "  status VARCHAR(30) NOT NULL, triggered_by VARCHAR(255) NOT NULL,\n" +
                    "  started_at DATETIME(6) NULL, finished_at DATETIME(6) NULL,\n" +
                    "  error_code VARCHAR(100) NULL, error_message TEXT NULL, created_at DATETIME(6) NOT NULL,\n" +
                    "  kyuubi_notebook_id VARCHAR(64) NULL, cancel_requested TINYINT(1) NOT NULL DEFAULT 0,\n" +
                    "  cells_total INT NOT NULL DEFAULT 0, cells_done INT NOT NULL DEFAULT 0,\n" +
                    "  current_cell_id VARCHAR(128) NULL, current_execution_id VARCHAR(128) NULL,\n" +
                    "  notebook_revision BIGINT NULL, heartbeat_at DATETIME(6) NULL, notebook_saved_at DATETIME(6) NULL,\n" +
                    "  PRIMARY KEY (id), KEY idx_notebook_runs_notebook_status (notebook_id, status),\n" +
                    "  KEY idx_notebook_runs_status_heartbeat (status, heartbeat_at)\n" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;");

            stmt.execute("CREATE TABLE notebook_cell_runs (\n" +
                    "  id CHAR(36) NOT NULL, notebook_run_id CHAR(36) NOT NULL, cell_id VARCHAR(100) NOT NULL,\n" +
                    "  execution_order INT NOT NULL, language VARCHAR(30) NOT NULL, status VARCHAR(30) NOT NULL,\n" +
                    "  started_at DATETIME(6) NULL, finished_at DATETIME(6) NULL,\n" +
                    "  row_count BIGINT NULL, execution_time_ms BIGINT NULL,\n" +
                    "  output_type VARCHAR(30) NULL, output_json LONGTEXT NULL, output_storage_uri VARCHAR(2048) NULL,\n" +
                    "  error_message TEXT NULL, execution_id VARCHAR(128) NULL, source TEXT NULL,\n" +
                    "  PRIMARY KEY (id), KEY fk_cell_run_notebook_run (notebook_run_id)\n" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;");

            stmt.execute("CREATE TABLE app_users (\n" +
                    "  id CHAR(36) NOT NULL, username VARCHAR(255) NOT NULL, display_name VARCHAR(255) NOT NULL,\n" +
                    "  email VARCHAR(255) NULL, status VARCHAR(32) NOT NULL, ranger_username VARCHAR(255) NOT NULL,\n" +
                    "  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,\n" +
                    "  PRIMARY KEY (id), UNIQUE KEY uk_app_users_username (username)\n" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;");

            // Dữ liệu sẵn có (tiếng Việt, hợp lệ với utf8mb3) để kiểm V2..V5 không làm mất/hỏng dữ liệu
            stmt.execute("INSERT INTO notebooks (id, node_id, notebook_format, default_language, document_json, created_at, updated_at) VALUES " +
                    "('00000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'SQLLAB_JSON_V1', 'SQL', " +
                    "'{\"schemaVersion\":1,\"defaultLanguage\":\"SQL\",\"metadata\":{},\"parameters\":[],\"cells\":[{\"id\":\"cell-1\",\"type\":\"CODE\",\"language\":\"SQL\",\"source\":\"SELECT ''Thống kê doanh thu''\"}]}', " +
                    "NOW(6), NOW(6))");
            stmt.execute("INSERT INTO notebook_revisions (id, notebook_id, revision_number, document_json, revision_type, created_by, created_at) VALUES " +
                    "('20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 1, '{}', 'MANUAL', 'nguyễn.văn.a', NOW(6))");
            stmt.execute("INSERT INTO notebook_runs (id, notebook_id, run_type, status, triggered_by, error_message, created_at) VALUES " +
                    "('30000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'CELL', 'FAILED', 'nguyễn.văn.a', 'Lỗi kết nối', NOW(6))");
            stmt.execute("INSERT INTO notebook_cell_runs (id, notebook_run_id, cell_id, execution_order, language, status, source, error_message) VALUES " +
                    "('40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'cell-1', 0, 'SQL', 'FAILED', 'SELECT 1', 'Lỗi')");
            stmt.execute("INSERT INTO app_users (id, username, display_name, status, ranger_username, created_at, updated_at) VALUES " +
                    "('50000000-0000-0000-0000-000000000001', 'alice', 'Alice Nguyễn', 'ACTIVE', 'alice', NOW(6), NOW(6))");
        }

        // 2. Khởi động Spring Boot NotebookServiceApplication với baseline-on-migrate=true, baseline-version=1
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(NotebookServiceApplication.class)
                .run(
                        "--spring.datasource.url=" + mariaDB.getJdbcUrl(),
                        "--spring.datasource.username=" + mariaDB.getUsername(),
                        "--spring.datasource.password=" + mariaDB.getPassword(),
                        "--spring.flyway.enabled=true",
                        "--spring.flyway.baseline-on-migrate=true",
                        "--spring.flyway.baseline-version=1",
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--notebook.auth.mode=oidc",
                        "--notebook.internalToken=secret-token-it",
                        "--notebook.internal-token=secret-token-it",
                        "--spring.main.web-application-type=servlet"
                )) {

            assertThat(context.isRunning()).isTrue();
        }

        // 3. Dữ liệu cũ còn nguyên, bảng đã utf8mb4_bin, cột mới có giá trị mặc định, lưu được ký tự 4 byte
        try (Connection conn = DriverManager.getConnection(mariaDB.getJdbcUrl(), mariaDB.getUsername(), mariaDB.getPassword());
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT document_json, node_id, name, owner FROM notebooks WHERE id = '00000000-0000-0000-0000-000000000001'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("document_json")).contains("Thống kê doanh thu");
                assertThat(rs.getString("node_id")).isEqualTo("10000000-0000-0000-0000-000000000001");
                assertThat(rs.getString("name")).isEmpty();
                assertThat(rs.getString("owner")).isEmpty();
            }
            try (ResultSet rs = stmt.executeQuery("SELECT error_message FROM notebook_runs WHERE id = '30000000-0000-0000-0000-000000000001'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Lỗi kết nối");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT table_name, table_collation FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_name IN ('notebooks','notebook_revisions','notebook_runs','notebook_cell_runs')")) {
                int n = 0;
                while (rs.next()) {
                    n++;
                    assertThat(rs.getString("table_collation")).as(rs.getString("table_name")).isEqualTo("utf8mb4_bin");
                }
                assertThat(n).isEqualTo(4);
            }
            // node_id cho phép NULL (notebook do notebook-api tạo), và emoji lưu được sau V3
            stmt.execute("INSERT INTO notebooks (id, node_id, notebook_format, default_language, document_json, created_at, updated_at, name, owner) VALUES " +
                    "('00000000-0000-0000-0000-000000000002', NULL, 'SQLLAB_JSON_V1', 'SQL', '{\"note\":\"📊\"}', NOW(6), NOW(6), 'Báo cáo 📊', 'alice')");
            try (ResultSet rs = stmt.executeQuery("SELECT name FROM notebooks WHERE id = '00000000-0000-0000-0000-000000000002'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("Báo cáo 📊");
            }
        }
    }
}
