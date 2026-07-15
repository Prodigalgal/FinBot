--liquibase formatted sql

--changeset codex:024-feature-parity-control-plane splitStatements:true endDelimiter:;
CREATE TABLE research_feedback (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    feedback_id VARCHAR(80) NOT NULL UNIQUE,
    workflow_run_id VARCHAR(80) NOT NULL UNIQUE
        REFERENCES workflow_run (run_id) ON DELETE CASCADE,
    rating VARCHAR(24) NOT NULL,
    effectiveness VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
    note VARCHAR(2000) NOT NULL DEFAULT '',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_research_feedback_id CHECK (
        feedback_id ~ '^feedback_[a-z0-9_-]{4,68}$'
    ),
    CONSTRAINT ck_research_feedback_rating CHECK (
        rating IN ('HELPFUL', 'NEUTRAL', 'NOT_HELPFUL')
    ),
    CONSTRAINT ck_research_feedback_effectiveness CHECK (
        effectiveness IN ('UNKNOWN', 'PENDING', 'WIN', 'LOSS', 'NO_TRADE')
    ),
    CONSTRAINT ck_research_feedback_version CHECK (version >= 0)
);

CREATE INDEX ix_research_feedback_updated
    ON research_feedback (updated_at DESC, id DESC);

CREATE TABLE setup_profile_application (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id VARCHAR(80) NOT NULL UNIQUE,
    idempotency_key VARCHAR(200) NOT NULL UNIQUE,
    profile_id VARCHAR(32) NOT NULL,
    applied_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
    preserved_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
    skipped_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
    applied_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_setup_profile_application_id CHECK (
        application_id ~ '^setup_[a-z0-9_-]{4,71}$'
    ),
    CONSTRAINT ck_setup_profile_id CHECK (
        profile_id IN ('RECOMMENDED', 'ECONOMY', 'DEEP_RESEARCH')
    ),
    CONSTRAINT ck_setup_profile_json CHECK (
        jsonb_typeof(applied_keys) = 'array'
        AND jsonb_typeof(preserved_keys) = 'array'
        AND jsonb_typeof(skipped_keys) = 'array'
    )
);

CREATE INDEX ix_setup_profile_application_time
    ON setup_profile_application (applied_at DESC, id DESC);

CREATE TABLE ai_experiment (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    experiment_id VARCHAR(80) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL,
    control_workflow_version_id VARCHAR(80) NOT NULL
        REFERENCES workflow_definition_version (version_id),
    candidate_workflow_version_id VARCHAR(80) NOT NULL
        REFERENCES workflow_definition_version (version_id),
    candidate_allocation_basis_points INTEGER NOT NULL,
    evaluation_metric VARCHAR(80) NOT NULL,
    minimum_sample_size INTEGER NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_ai_experiment_id CHECK (
        experiment_id ~ '^experiment_[a-z0-9_-]{4,66}$'
    ),
    CONSTRAINT ck_ai_experiment_status CHECK (
        status IN ('DRAFT', 'RUNNING', 'PAUSED', 'COMPLETED')
    ),
    CONSTRAINT ck_ai_experiment_versions CHECK (
        control_workflow_version_id <> candidate_workflow_version_id
    ),
    CONSTRAINT ck_ai_experiment_allocation CHECK (
        candidate_allocation_basis_points BETWEEN 1 AND 9999
    ),
    CONSTRAINT ck_ai_experiment_sample CHECK (minimum_sample_size BETWEEN 2 AND 100000),
    CONSTRAINT ck_ai_experiment_version CHECK (version >= 0)
);

CREATE INDEX ix_ai_experiment_status_updated
    ON ai_experiment (status, updated_at DESC, id DESC);

ALTER TABLE workflow_run
    ADD COLUMN requested_workflow_version_id VARCHAR(80)
        REFERENCES workflow_definition_version (version_id),
    ADD COLUMN ai_experiment_id VARCHAR(80)
        REFERENCES ai_experiment (experiment_id),
    ADD COLUMN ai_experiment_variant VARCHAR(16),
    ADD CONSTRAINT ck_workflow_run_experiment_assignment CHECK (
        (ai_experiment_id IS NULL AND ai_experiment_variant IS NULL)
        OR (ai_experiment_id IS NOT NULL AND ai_experiment_variant IN ('CONTROL', 'CANDIDATE'))
    );

CREATE INDEX ix_workflow_run_experiment_assignment
    ON workflow_run (ai_experiment_id, ai_experiment_variant, accepted_at DESC)
    WHERE ai_experiment_id IS NOT NULL;

CREATE TABLE network_diagnostic_batch (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id VARCHAR(80) NOT NULL UNIQUE,
    idempotency_key VARCHAR(200) NOT NULL UNIQUE,
    request_fingerprint CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_network_diagnostic_batch_id CHECK (
        batch_id ~ '^diagnosticbatch_[a-z0-9_-]{4,59}$'
    ),
    CONSTRAINT ck_network_diagnostic_batch_fingerprint CHECK (
        request_fingerprint ~ '^[a-f0-9]{64}$'
    )
);

CREATE TABLE network_diagnostic_run (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    diagnostic_id VARCHAR(80) NOT NULL UNIQUE,
    batch_idempotency_key VARCHAR(200) NOT NULL
        REFERENCES network_diagnostic_batch (idempotency_key),
    route_type VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    proxy_configured BOOLEAN NOT NULL,
    proxied BOOLEAN NOT NULL,
    safe_endpoint VARCHAR(240) NOT NULL,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    started_at TIMESTAMPTZ NOT NULL,
    http_status INTEGER,
    latency_milliseconds BIGINT,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_network_diagnostic_id CHECK (
        diagnostic_id ~ '^diagnostic_[a-z0-9_-]{4,64}$'
    ),
    CONSTRAINT ck_network_diagnostic_route CHECK (
        route_type IN ('FIRECRAWL', 'EXCHANGE_GATE', 'EXCHANGE_BYBIT', 'PUBLIC_DATA')
    ),
    CONSTRAINT ck_network_diagnostic_status CHECK (
        status IN ('RUNNING', 'READY', 'BLOCKED', 'FAILED')
    ),
    CONSTRAINT ck_network_diagnostic_http CHECK (
        http_status IS NULL OR http_status BETWEEN 100 AND 599
    ),
    CONSTRAINT ck_network_diagnostic_latency CHECK (
        latency_milliseconds IS NULL OR latency_milliseconds >= 0
    ),
    CONSTRAINT ck_network_diagnostic_time CHECK (
        completed_at IS NULL OR completed_at >= started_at
    ),
    CONSTRAINT ck_network_diagnostic_terminal CHECK (
        (status = 'RUNNING' AND completed_at IS NULL)
        OR (status <> 'RUNNING' AND completed_at IS NOT NULL)
    ),
    CONSTRAINT uq_network_diagnostic_batch_route UNIQUE (batch_idempotency_key, route_type)
);

CREATE INDEX ix_network_diagnostic_route_time
    ON network_diagnostic_run (route_type, started_at DESC, id DESC);

UPDATE system_setting
SET value_text = '25.00',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE setting_key = 'ai.max_cost_usd_per_run'
  AND source = 'DEFAULT'
  AND value_text = '0.50';

--rollback UPDATE system_setting SET value_text = '0.50', version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE setting_key = 'ai.max_cost_usd_per_run' AND source = 'DEFAULT' AND value_text = '25.00';
--rollback DROP TABLE IF EXISTS network_diagnostic_run;
--rollback DROP TABLE IF EXISTS network_diagnostic_batch;
--rollback DROP INDEX IF EXISTS ix_workflow_run_experiment_assignment;
--rollback ALTER TABLE workflow_run DROP CONSTRAINT IF EXISTS ck_workflow_run_experiment_assignment;
--rollback ALTER TABLE workflow_run DROP COLUMN IF EXISTS ai_experiment_variant;
--rollback ALTER TABLE workflow_run DROP COLUMN IF EXISTS ai_experiment_id;
--rollback ALTER TABLE workflow_run DROP COLUMN IF EXISTS requested_workflow_version_id;
--rollback DROP TABLE IF EXISTS ai_experiment;
--rollback DROP TABLE IF EXISTS setup_profile_application;
--rollback DROP TABLE IF EXISTS research_feedback;
