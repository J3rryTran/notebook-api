-- V4__foreign_keys.sql: Thêm khóa ngoại cho notebook_permissions và notebook_execution_logs sau khi V3 đã đồng bộ bảng cha sang utf8mb4_bin

DELIMITER //

CREATE PROCEDURE AddForeignKeysIfNotExists()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_np_notebook'
  ) THEN
    ALTER TABLE notebook_permissions ADD CONSTRAINT fk_np_notebook FOREIGN KEY (notebook_id) REFERENCES notebooks (id) ON DELETE CASCADE;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_nel_cell_run'
  ) THEN
    ALTER TABLE notebook_execution_logs ADD CONSTRAINT fk_nel_cell_run FOREIGN KEY (cell_run_id) REFERENCES notebook_cell_runs (id) ON DELETE CASCADE;
  END IF;
END //

DELIMITER ;

CALL AddForeignKeysIfNotExists();
DROP PROCEDURE IF EXISTS AddForeignKeysIfNotExists;
