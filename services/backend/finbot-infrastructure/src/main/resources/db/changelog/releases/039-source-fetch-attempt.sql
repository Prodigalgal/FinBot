--liquibase formatted sql

--changeset codex:039-source-fetch-attempt splitStatements:true endDelimiter:;
CREATE TABLE source_fetch_attempt (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    attempt_id VARCHAR(80) NOT NULL UNIQUE,
    collection_id VARCHAR(80) NOT NULL REFERENCES source_collection_run (collection_id) ON DELETE CASCADE,
    source_id VARCHAR(80) NOT NULL REFERENCES information_source (source_id),
    requested_url TEXT,
    route_type VARCHAR(32) NOT NULL,
    status_code INTEGER,
    content_type VARCHAR(160),
    response_bytes INTEGER NOT NULL DEFAULT 0,
    retry_count INTEGER NOT NULL DEFAULT 0,
    outcome VARCHAR(24) NOT NULL,
    error_code VARCHAR(80),
    parser_version VARCHAR(120),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_source_fetch_attempt_id CHECK (attempt_id ~ '^fetch_[a-z0-9_-]{4,72}$'),
    CONSTRAINT ck_source_fetch_attempt_route CHECK (
        route_type IN ('WEB_CRAWL', 'FIRECRAWL', 'EXCHANGE_GATE', 'EXCHANGE_BYBIT', 'PUBLIC_DATA', 'DIRECT')
    ),
    CONSTRAINT ck_source_fetch_attempt_status CHECK (status_code IS NULL OR status_code BETWEEN 100 AND 599),
    CONSTRAINT ck_source_fetch_attempt_counters CHECK (response_bytes >= 0 AND retry_count >= 0),
    CONSTRAINT ck_source_fetch_attempt_outcome CHECK (outcome IN ('PREPARED', 'FAILED', 'BLOCKED')),
    CONSTRAINT ck_source_fetch_attempt_time CHECK (completed_at >= started_at)
);

CREATE INDEX ix_source_fetch_attempt_collection
    ON source_fetch_attempt (collection_id, started_at, id);
CREATE INDEX ix_source_fetch_attempt_source
    ON source_fetch_attempt (source_id, started_at DESC, id DESC);
CREATE INDEX ix_source_fetch_attempt_outcome
    ON source_fetch_attempt (outcome, started_at DESC, id DESC);

--rollback DROP TABLE IF EXISTS source_fetch_attempt;
