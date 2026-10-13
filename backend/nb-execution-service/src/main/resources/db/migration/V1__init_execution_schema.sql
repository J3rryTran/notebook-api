-- Flyway V1 migration for nb-execution-service (schema nb_execution)
CREATE SCHEMA IF NOT EXISTS nb_execution;

CREATE TABLE IF NOT EXISTS nb_execution.notebook_sessions (
    id VARCHAR(64) PRIMARY KEY,
    notebook_id VARCHAR(64) NOT NULL,
    kyuubi_session_id VARCHAR(64),
    kyuubi_instance VARCHAR(256),
    user_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'INITIALIZING',
    execution_count_seq INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS nb_execution.notebook_executions (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL REFERENCES nb_execution.notebook_sessions(id) ON DELETE CASCADE,
    notebook_id VARCHAR(64) NOT NULL,
    cell_id VARCHAR(64) NOT NULL,
    kyuubi_operation_id VARCHAR(64),
    execution_count INT,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    statement TEXT,
    language VARCHAR(32),
    submitted_by VARCHAR(128) NOT NULL,
    sequence_number BIGINT NOT NULL DEFAULT 0,
    log_output TEXT,
    result_output TEXT,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
