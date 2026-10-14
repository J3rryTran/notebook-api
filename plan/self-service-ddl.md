# DDL và Mẫu Dữ liệu MariaDB (database test, schema theo self_service)

Tài liệu tham khảo theo kế hoạch `plan/mariadb-auth-plan.md` (S0).

## 1. DDL 5 bảng có sẵn (database `test`)

```sql
CREATE TABLE notebooks (
  id CHAR(36) NOT NULL, node_id CHAR(36) NOT NULL,
  notebook_format VARCHAR(30) NOT NULL, default_language VARCHAR(30) NOT NULL,
  document_json LONGTEXT NOT NULL,
  current_revision BIGINT NOT NULL DEFAULT 1, lock_version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
  zeppelin_note_id VARCHAR(64) NULL, kyuubi_notebook_id VARCHAR(64) NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_notebook_node (node_id), KEY idx_notebook_kyuubi_notebook (kyuubi_notebook_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_general_ci;

CREATE TABLE notebook_revisions (
  id CHAR(36) NOT NULL, notebook_id CHAR(36) NOT NULL, revision_number BIGINT NOT NULL,
  document_json LONGTEXT NOT NULL, revision_type VARCHAR(30) NOT NULL,
  created_by VARCHAR(255) NOT NULL, created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_notebook_revision (notebook_id, revision_number),
  KEY idx_notebook_revisions_created_at (created_at, notebook_id, revision_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE notebook_runs (
  id CHAR(36) NOT NULL, notebook_id CHAR(36) NOT NULL, run_type VARCHAR(30) NOT NULL,
  status VARCHAR(30) NOT NULL, triggered_by VARCHAR(255) NOT NULL,
  started_at DATETIME(6) NULL, finished_at DATETIME(6) NULL,
  error_code VARCHAR(100) NULL, error_message TEXT NULL, created_at DATETIME(6) NOT NULL,
  kyuubi_notebook_id VARCHAR(64) NULL, cancel_requested TINYINT(1) NOT NULL DEFAULT 0,
  cells_total INT NOT NULL DEFAULT 0, cells_done INT NOT NULL DEFAULT 0,
  current_cell_id VARCHAR(128) NULL, current_execution_id VARCHAR(128) NULL,
  notebook_revision BIGINT NULL, heartbeat_at DATETIME(6) NULL, notebook_saved_at DATETIME(6) NULL,
  PRIMARY KEY (id), KEY idx_notebook_runs_notebook_status (notebook_id, status),
  KEY idx_notebook_runs_status_heartbeat (status, heartbeat_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE notebook_cell_runs (
  id CHAR(36) NOT NULL, notebook_run_id CHAR(36) NOT NULL, cell_id VARCHAR(100) NOT NULL,
  execution_order INT NOT NULL, language VARCHAR(30) NOT NULL, status VARCHAR(30) NOT NULL,
  started_at DATETIME(6) NULL, finished_at DATETIME(6) NULL,
  row_count BIGINT NULL, execution_time_ms BIGINT NULL,
  output_type VARCHAR(30) NULL, output_json LONGTEXT NULL, output_storage_uri VARCHAR(2048) NULL,
  error_message TEXT NULL, execution_id VARCHAR(128) NULL, source TEXT NULL,
  PRIMARY KEY (id), KEY fk_cell_run_notebook_run (notebook_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE app_users (
  id CHAR(36) NOT NULL, username VARCHAR(255) NOT NULL, display_name VARCHAR(255) NOT NULL,
  email VARCHAR(255) NULL, status VARCHAR(32) NOT NULL, ranger_username VARCHAR(255) NOT NULL,
  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_app_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

## 2. Mẫu document_json định dạng SQLLAB_JSON_V1

```json
{
  "schemaVersion": 1,
  "defaultLanguage": "SQL",
  "metadata": {
    "name": "Sample Notebook",
    "description": "Created via notebook-api",
    "defaultCatalog": "spark_catalog",
    "defaultSchema": "default"
  },
  "parameters": [],
  "cells": [
    {
      "id": "cell-315387a0-9c2e-4b67-a249-14a02c5c991e",
      "type": "CODE",
      "language": "SQL",
      "source": "SELECT 1 AS sample_col",
      "metadata": {
        "kyuubiCellId": "b523460e-827d-419a-9e12-32ba53ce9112",
        "executionCount": 1,
        "collapsed": false
      }
    }
  ]
}
```
