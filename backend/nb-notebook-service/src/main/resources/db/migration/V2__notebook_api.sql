-- V2__notebook_api.sql: Cập nhật cấu trúc bảng cho notebook-api và tạo 5 bảng mới

-- 1. Cập nhật bảng notebooks
ALTER TABLE notebooks MODIFY node_id CHAR(36) NULL;
ALTER TABLE notebooks ADD COLUMN IF NOT EXISTS name VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE notebooks ADD COLUMN IF NOT EXISTS owner VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE notebooks ADD COLUMN IF NOT EXISTS folder_id CHAR(36) NULL;
ALTER TABLE notebooks ADD COLUMN IF NOT EXISTS description LONGTEXT NULL;
ALTER TABLE notebooks ADD COLUMN IF NOT EXISTS deleted_at DATETIME(6) NULL;

CREATE INDEX IF NOT EXISTS idx_notebooks_owner ON notebooks (owner, deleted_at);
CREATE INDEX IF NOT EXISTS idx_notebooks_folder ON notebooks (folder_id);

-- 2. Cập nhật bảng notebook_runs
ALTER TABLE notebook_runs ADD COLUMN IF NOT EXISTS kyuubi_session_ref CHAR(36) NULL;

-- 3. Cập nhật bảng notebook_cell_runs
ALTER TABLE notebook_cell_runs ADD COLUMN IF NOT EXISTS execution_count INT NULL;
ALTER TABLE notebook_cell_runs MODIFY source LONGTEXT;
ALTER TABLE notebook_cell_runs MODIFY error_message LONGTEXT;

-- 4. Bảng notebook_folders
CREATE TABLE IF NOT EXISTS notebook_folders (
  id CHAR(36) NOT NULL,
  name VARCHAR(255) NOT NULL,
  parent_id CHAR(36) NULL,
  owner VARCHAR(255) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  deleted_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  KEY idx_nf_owner (owner, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 5. Bảng notebook_permissions
CREATE TABLE IF NOT EXISTS notebook_permissions (
  id BIGINT AUTO_INCREMENT NOT NULL,
  notebook_id CHAR(36) NOT NULL,
  principal_type VARCHAR(32) NOT NULL,
  principal_id VARCHAR(255) NOT NULL,
  role VARCHAR(32) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_np_notebook_principal (notebook_id, principal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 6. Bảng notebook_kyuubi_sessions
CREATE TABLE IF NOT EXISTS notebook_kyuubi_sessions (
  id CHAR(36) NOT NULL,
  notebook_id CHAR(36) NOT NULL,
  user_id VARCHAR(255) NOT NULL,
  kyuubi_session_id VARCHAR(64) NOT NULL,
  kyuubi_instance VARCHAR(256) NULL,
  status VARCHAR(32) NOT NULL,
  execution_count_seq INT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  last_used_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_nks_notebook_user_status (notebook_id, user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 7. Bảng notebook_execution_logs
CREATE TABLE IF NOT EXISTS notebook_execution_logs (
  id BIGINT AUTO_INCREMENT NOT NULL,
  cell_run_id CHAR(36) NOT NULL,
  sequence BIGINT NOT NULL,
  line LONGTEXT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_nel_cell_seq (cell_run_id, sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 8. Bảng notebook_auth_sessions
CREATE TABLE IF NOT EXISTS notebook_auth_sessions (
  id VARCHAR(64) NOT NULL,
  username VARCHAR(255) NOT NULL,
  auth_mode VARCHAR(16) NOT NULL,
  credential_enc VARBINARY(1024) NOT NULL,
  is_admin BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  last_seen_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  last_recheck_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  expires_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_nas_user (username),
  KEY idx_nas_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
