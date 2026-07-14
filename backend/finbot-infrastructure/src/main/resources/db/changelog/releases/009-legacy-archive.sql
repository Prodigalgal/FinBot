--liquibase formatted sql

--changeset codex:009-legacy-archive splitStatements:true endDelimiter:;
CREATE TABLE legacy_import_manifest (
    import_id VARCHAR(80) PRIMARY KEY,
    source_format VARCHAR(24) NOT NULL,
    source_name VARCHAR(255) NOT NULL,
    source_sha256 CHAR(64) NOT NULL UNIQUE,
    source_byte_size BIGINT NOT NULL,
    schema_sha256 CHAR(64) NOT NULL,
    tool_version VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL,
    source_table_count INTEGER NOT NULL DEFAULT 0,
    source_row_count BIGINT NOT NULL DEFAULT 0,
    archived_row_count BIGINT NOT NULL DEFAULT 0,
    transformed_row_count BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    error_summary TEXT,
    CONSTRAINT ck_legacy_import_id CHECK (import_id ~ '^legacy_[a-f0-9]{24}$'),
    CONSTRAINT ck_legacy_import_format CHECK (source_format IN ('SQLITE', 'POSTGRESQL')),
    CONSTRAINT ck_legacy_import_hashes CHECK (
        source_sha256 ~ '^[0-9a-f]{64}$' AND schema_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_legacy_import_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_legacy_import_counts CHECK (
        source_byte_size >= 0 AND source_table_count >= 0 AND source_row_count >= 0
        AND archived_row_count >= 0 AND transformed_row_count >= 0
    ),
    CONSTRAINT ck_legacy_import_time CHECK (
        completed_at IS NULL OR completed_at >= started_at
    )
);

CREATE TABLE legacy_import_table (
    import_id VARCHAR(80) NOT NULL REFERENCES legacy_import_manifest (import_id) ON DELETE CASCADE,
    source_table VARCHAR(128) NOT NULL,
    disposition VARCHAR(32) NOT NULL,
    target_entity VARCHAR(128) NOT NULL,
    source_row_count BIGINT NOT NULL,
    archived_row_count BIGINT NOT NULL DEFAULT 0,
    transformed_row_count BIGINT NOT NULL DEFAULT 0,
    content_sha256 CHAR(64),
    status VARCHAR(24) NOT NULL,
    error_summary TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (import_id, source_table),
    CONSTRAINT ck_legacy_import_table_name CHECK (source_table ~ '^[A-Za-z_][A-Za-z0-9_]{0,127}$'),
    CONSTRAINT ck_legacy_import_table_disposition CHECK (
        disposition IN ('ARCHIVED', 'TRANSFORMED_AND_ARCHIVED', 'NOT_APPLICABLE')
    ),
    CONSTRAINT ck_legacy_import_table_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_legacy_import_table_counts CHECK (
        source_row_count >= 0 AND archived_row_count >= 0 AND transformed_row_count >= 0
    ),
    CONSTRAINT ck_legacy_import_table_hash CHECK (
        content_sha256 IS NULL OR content_sha256 ~ '^[0-9a-f]{64}$'
    )
);

CREATE TABLE legacy_archive_row (
    import_id VARCHAR(80) NOT NULL,
    source_table VARCHAR(128) NOT NULL,
    row_ordinal BIGINT NOT NULL,
    source_row_key VARCHAR(255) NOT NULL,
    content JSONB NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (import_id, source_table, row_ordinal),
    CONSTRAINT fk_legacy_archive_table FOREIGN KEY (import_id, source_table)
        REFERENCES legacy_import_table (import_id, source_table) ON DELETE CASCADE,
    CONSTRAINT ck_legacy_archive_ordinal CHECK (row_ordinal > 0),
    CONSTRAINT ck_legacy_archive_content CHECK (jsonb_typeof(content) = 'object'),
    CONSTRAINT ck_legacy_archive_hash CHECK (content_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_legacy_archive_table_key
    ON legacy_archive_row (source_table, source_row_key, import_id);

CREATE INDEX ix_legacy_import_completed
    ON legacy_import_manifest (status, completed_at DESC, import_id);

--rollback DROP TABLE IF EXISTS legacy_archive_row;
--rollback DROP TABLE IF EXISTS legacy_import_table;
--rollback DROP TABLE IF EXISTS legacy_import_manifest;
