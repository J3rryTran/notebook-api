-- Flyway V1 migration for nb-document-service (schema nb_document)
CREATE SCHEMA IF NOT EXISTS nb_document;

CREATE TABLE IF NOT EXISTS nb_document.notebook_folders (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    parent_id VARCHAR(64),
    owner VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS nb_document.notebooks (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    folder_id VARCHAR(64),
    description TEXT,
    default_catalog VARCHAR(128),
    default_schema VARCHAR(128),
    owner VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS nb_document.notebook_cells (
    id VARCHAR(64) PRIMARY KEY,
    notebook_id VARCHAR(64) NOT NULL REFERENCES nb_document.notebooks(id) ON DELETE CASCADE,
    cell_type VARCHAR(32) NOT NULL,
    language VARCHAR(32) NOT NULL,
    source TEXT NOT NULL,
    position INT NOT NULL,
    execution_count INT,
    metadata_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS nb_document.notebook_permissions (
    id BIGSERIAL PRIMARY KEY,
    notebook_id VARCHAR(64) NOT NULL REFERENCES nb_document.notebooks(id) ON DELETE CASCADE,
    principal_type VARCHAR(32) NOT NULL,
    principal_id VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_nb_permission UNIQUE (notebook_id, principal_id)
);

CREATE TABLE IF NOT EXISTS nb_document.notebook_revisions (
    id BIGSERIAL PRIMARY KEY,
    notebook_id VARCHAR(64) NOT NULL REFERENCES nb_document.notebooks(id) ON DELETE CASCADE,
    revision_number BIGINT NOT NULL,
    reason TEXT,
    is_protected BOOLEAN NOT NULL DEFAULT FALSE,
    document_json TEXT NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
