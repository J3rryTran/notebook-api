-- V3__utf8mb4.sql: Chuyển đổi 4 bảng cũ sang utf8mb4_bin để lưu emoji và ký tự 4-byte
ALTER TABLE notebooks CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
ALTER TABLE notebook_revisions CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
ALTER TABLE notebook_runs CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
ALTER TABLE notebook_cell_runs CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
