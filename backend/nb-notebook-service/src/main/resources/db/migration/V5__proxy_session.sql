-- V5__proxy_session.sql: Thêm cột proxied và effective_user vào notebook_kyuubi_sessions cho B4

ALTER TABLE notebook_kyuubi_sessions ADD COLUMN IF NOT EXISTS proxied BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE notebook_kyuubi_sessions ADD COLUMN IF NOT EXISTS effective_user VARCHAR(255) NULL;
