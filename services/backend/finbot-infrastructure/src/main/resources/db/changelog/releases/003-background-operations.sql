--liquibase formatted sql

--changeset codex:003-background-operations splitStatements:true endDelimiter:;
CREATE TABLE background_task (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id VARCHAR(80) NOT NULL UNIQUE,
    task_type VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL,
    priority SMALLINT NOT NULL DEFAULT 50,
    idempotency_key VARCHAR(200) NOT NULL UNIQUE,
    payload JSONB NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    maximum_attempts INTEGER NOT NULL DEFAULT 3,
    available_at TIMESTAMPTZ NOT NULL,
    claimed_at TIMESTAMPTZ,
    lease_expires_at TIMESTAMPTZ,
    claim_owner VARCHAR(120),
    heartbeat_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    error_code VARCHAR(80),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_background_task_id CHECK (task_id ~ '^task_[a-z0-9_-]{4,72}$'),
    CONSTRAINT ck_background_task_type CHECK (task_type IN (
        'SCHEDULED_RESEARCH', 'INSTANT_RESEARCH', 'ACCOUNT_SYNC',
        'ORDER_RECONCILIATION', 'MARKET_DATA_SYNC', 'INGESTION'
    )),
    CONSTRAINT ck_background_task_status CHECK (status IN (
        'PENDING', 'CLAIMED', 'COMPLETED', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT ck_background_task_priority CHECK (priority BETWEEN 0 AND 100),
    CONSTRAINT ck_background_task_attempts CHECK (
        attempt_count >= 0 AND maximum_attempts BETWEEN 1 AND 20
        AND attempt_count <= maximum_attempts
    ),
    CONSTRAINT ck_background_task_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_background_task_claim CHECK (
        (status = 'CLAIMED'
            AND claimed_at IS NOT NULL
            AND lease_expires_at IS NOT NULL
            AND claim_owner IS NOT NULL
            AND heartbeat_at IS NOT NULL)
        OR status <> 'CLAIMED'
    ),
    CONSTRAINT ck_background_task_terminal CHECK (
        (status IN ('COMPLETED', 'FAILED', 'CANCELLED') AND completed_at IS NOT NULL)
        OR status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
    )
);

CREATE INDEX ix_background_task_due
    ON background_task (priority DESC, available_at, id)
    WHERE status = 'PENDING';

CREATE INDEX ix_background_task_expired_lease
    ON background_task (lease_expires_at, id)
    WHERE status = 'CLAIMED';

CREATE INDEX ix_background_task_history
    ON background_task (status, created_at DESC, id DESC);

CREATE TABLE worker_instance (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    worker_id VARCHAR(80) NOT NULL UNIQUE,
    instance_name VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    heartbeat_at TIMESTAMPTZ NOT NULL,
    stopped_at TIMESTAMPTZ,
    CONSTRAINT ck_worker_instance_id CHECK (worker_id ~ '^worker_[a-z0-9_-]{4,70}$'),
    CONSTRAINT ck_worker_instance_status CHECK (status IN ('RUNNING', 'STOPPING', 'STOPPED')),
    CONSTRAINT ck_worker_instance_time CHECK (
        heartbeat_at >= started_at AND (stopped_at IS NULL OR stopped_at >= started_at)
    )
);

CREATE INDEX ix_worker_instance_status
    ON worker_instance (status, heartbeat_at DESC, id DESC);

CREATE TABLE schedule_definition (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    schedule_id VARCHAR(80) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    task_type VARCHAR(40) NOT NULL,
    payload JSONB NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    interval_seconds INTEGER NOT NULL,
    priority SMALLINT NOT NULL DEFAULT 50,
    maximum_attempts INTEGER NOT NULL DEFAULT 3,
    next_run_at TIMESTAMPTZ NOT NULL,
    last_scheduled_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_schedule_definition_id CHECK (schedule_id ~ '^schedule_[a-z0-9_-]{4,68}$'),
    CONSTRAINT ck_schedule_definition_task_type CHECK (task_type IN (
        'SCHEDULED_RESEARCH', 'ACCOUNT_SYNC', 'ORDER_RECONCILIATION',
        'MARKET_DATA_SYNC', 'INGESTION'
    )),
    CONSTRAINT ck_schedule_definition_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_schedule_definition_interval CHECK (interval_seconds BETWEEN 10 AND 2592000),
    CONSTRAINT ck_schedule_definition_priority CHECK (priority BETWEEN 0 AND 100),
    CONSTRAINT ck_schedule_definition_attempts CHECK (maximum_attempts BETWEEN 1 AND 20),
    CONSTRAINT ck_schedule_definition_version CHECK (version >= 0)
);

CREATE INDEX ix_schedule_definition_due
    ON schedule_definition (next_run_at, id)
    WHERE enabled;

INSERT INTO schedule_definition (
    schedule_id, display_name, task_type, payload, enabled, interval_seconds,
    priority, maximum_attempts, next_run_at
) VALUES
    ('schedule_autonomous_research', '自动研究循环', 'SCHEDULED_RESEARCH',
     '{"requestSummary":"执行定时产品研究闭环"}'::jsonb, TRUE, 3600, 60, 3, CURRENT_TIMESTAMP),
    ('schedule_gate_account_sync', 'Gate TestNet 账户同步', 'ACCOUNT_SYNC',
     '{"accountId":"account_gate_testnet_default"}'::jsonb, TRUE, 60, 80, 5, CURRENT_TIMESTAMP),
    ('schedule_bybit_account_sync', 'Bybit Demo 账户同步', 'ACCOUNT_SYNC',
     '{"accountId":"account_bybit_demo_default"}'::jsonb, TRUE, 60, 80, 5, CURRENT_TIMESTAMP);

--rollback DROP TABLE IF EXISTS schedule_definition;
--rollback DROP TABLE IF EXISTS worker_instance;
--rollback DROP TABLE IF EXISTS background_task;
