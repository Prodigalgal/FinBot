--liquibase formatted sql

--changeset codex:001-foundation splitStatements:true endDelimiter:;
CREATE TABLE workflow_run (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id VARCHAR(80) NOT NULL UNIQUE,
    workflow_type VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL,
    trigger_type VARCHAR(24) NOT NULL,
    request_summary TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    accepted_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_workflow_run_id CHECK (run_id ~ '^run_[a-z0-9_-]{4,75}$'),
    CONSTRAINT ck_workflow_run_type CHECK (workflow_type IN (
        'SCHEDULED_RESEARCH', 'INSTANT_RESEARCH', 'ACCOUNT_RECONCILIATION', 'EXCHANGE_LEDGER_SYNC'
    )),
    CONSTRAINT ck_workflow_run_status CHECK (status IN (
        'ACCEPTED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT ck_workflow_run_trigger CHECK (trigger_type IN ('MANUAL', 'SCHEDULED', 'API', 'RECOVERY')),
    CONSTRAINT ck_workflow_run_version CHECK (version >= 0),
    CONSTRAINT ck_workflow_run_time_order CHECK (
        (started_at IS NULL OR started_at >= accepted_at)
        AND (completed_at IS NULL OR completed_at >= accepted_at)
    )
);

CREATE INDEX ix_workflow_run_status_updated
    ON workflow_run (status, updated_at DESC, id DESC);

CREATE TABLE workflow_event (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id VARCHAR(80) NOT NULL UNIQUE,
    run_id VARCHAR(80) NOT NULL REFERENCES workflow_run (run_id) ON DELETE CASCADE,
    sequence BIGINT NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_workflow_event_run_sequence UNIQUE (run_id, sequence),
    CONSTRAINT ck_workflow_event_id CHECK (event_id ~ '^event_[a-z0-9_-]{4,73}$'),
    CONSTRAINT ck_workflow_event_sequence CHECK (sequence > 0),
    CONSTRAINT ck_workflow_event_payload CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX ix_workflow_event_occurred
    ON workflow_event (occurred_at DESC, id DESC);

CREATE TABLE outbox_event (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id VARCHAR(80) NOT NULL UNIQUE,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(80) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL,
    claimed_at TIMESTAMPTZ,
    claim_owner VARCHAR(120),
    published_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'CLAIMED', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_outbox_claim CHECK (
        (status = 'CLAIMED' AND claimed_at IS NOT NULL AND claim_owner IS NOT NULL)
        OR status <> 'CLAIMED'
    )
);

CREATE INDEX ix_outbox_due
    ON outbox_event (status, available_at, id)
    WHERE status IN ('PENDING', 'FAILED');

CREATE TABLE trade_decision (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    decision_id VARCHAR(80) NOT NULL UNIQUE,
    workflow_run_id VARCHAR(80) NOT NULL REFERENCES workflow_run (run_id),
    symbol VARCHAR(32) NOT NULL,
    decision_kind VARCHAR(24) NOT NULL,
    action VARCHAR(16) NOT NULL,
    confidence NUMERIC(5, 4) NOT NULL,
    entry_reference NUMERIC(38, 18),
    target_price NUMERIC(38, 18),
    invalidation_price NUMERIC(38, 18),
    rationale JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_trade_decision_id_action UNIQUE (decision_id, action),
    CONSTRAINT ck_trade_decision_id CHECK (decision_id ~ '^decision_[a-z0-9_-]{4,68}$'),
    CONSTRAINT ck_trade_decision_symbol CHECK (symbol ~ '^[A-Z0-9_]{2,32}$'),
    CONSTRAINT ck_trade_decision_kind CHECK (decision_kind IN ('DIRECTIONAL', 'NON_DIRECTIONAL')),
    CONSTRAINT ck_trade_decision_action CHECK (action IN ('BUY', 'SELL', 'HOLD', 'WATCH')),
    CONSTRAINT ck_trade_decision_confidence CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT ck_trade_decision_rationale CHECK (jsonb_typeof(rationale) = 'array'),
    CONSTRAINT ck_trade_decision_direction CHECK (
        (decision_kind = 'DIRECTIONAL'
            AND action IN ('BUY', 'SELL')
            AND entry_reference > 0
            AND target_price > 0
            AND invalidation_price > 0)
        OR
        (decision_kind = 'NON_DIRECTIONAL'
            AND action IN ('HOLD', 'WATCH')
            AND entry_reference IS NULL
            AND target_price IS NULL
            AND invalidation_price IS NULL)
    )
);

CREATE INDEX ix_trade_decision_run_created
    ON trade_decision (workflow_run_id, created_at DESC, id DESC);

CREATE TABLE trade_proposal (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    proposal_id VARCHAR(80) NOT NULL UNIQUE,
    decision_id VARCHAR(80) NOT NULL,
    symbol VARCHAR(32) NOT NULL,
    action VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    entry_reference NUMERIC(38, 18) NOT NULL,
    target_price NUMERIC(38, 18) NOT NULL,
    invalidation_price NUMERIC(38, 18) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    superseded_at TIMESTAMPTZ,
    CONSTRAINT uq_trade_proposal_id_action UNIQUE (proposal_id, action),
    CONSTRAINT fk_trade_proposal_directional_decision
        FOREIGN KEY (decision_id, action) REFERENCES trade_decision (decision_id, action),
    CONSTRAINT ck_trade_proposal_id CHECK (proposal_id ~ '^proposal_[a-z0-9_-]{4,68}$'),
    CONSTRAINT ck_trade_proposal_action CHECK (action IN ('BUY', 'SELL')),
    CONSTRAINT ck_trade_proposal_status CHECK (status IN ('GENERATED', 'SUPERSEDED', 'EXPIRED')),
    CONSTRAINT ck_trade_proposal_prices CHECK (
        entry_reference > 0 AND target_price > 0 AND invalidation_price > 0
    )
);

CREATE INDEX ix_trade_proposal_decision_created
    ON trade_proposal (decision_id, created_at DESC, id DESC);

CREATE TABLE approved_trade_intent (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    intent_id VARCHAR(80) NOT NULL UNIQUE,
    proposal_id VARCHAR(80) NOT NULL UNIQUE,
    symbol VARCHAR(32) NOT NULL,
    action VARCHAR(16) NOT NULL,
    quantity NUMERIC(38, 18) NOT NULL,
    entry_reference NUMERIC(38, 18) NOT NULL,
    target_price NUMERIC(38, 18) NOT NULL,
    invalidation_price NUMERIC(38, 18) NOT NULL,
    policy_version VARCHAR(80) NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_trade_intent_id_action UNIQUE (intent_id, action),
    CONSTRAINT fk_trade_intent_directional_proposal
        FOREIGN KEY (proposal_id, action) REFERENCES trade_proposal (proposal_id, action),
    CONSTRAINT ck_trade_intent_id CHECK (intent_id ~ '^intent_[a-z0-9_-]{4,70}$'),
    CONSTRAINT ck_trade_intent_action CHECK (action IN ('BUY', 'SELL')),
    CONSTRAINT ck_trade_intent_values CHECK (
        quantity > 0 AND entry_reference > 0 AND target_price > 0 AND invalidation_price > 0
    )
);

CREATE TABLE oms_order (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id VARCHAR(80) NOT NULL UNIQUE,
    intent_id VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL UNIQUE,
    exchange VARCHAR(24) NOT NULL,
    account_ref VARCHAR(80) NOT NULL,
    symbol VARCHAR(32) NOT NULL,
    side VARCHAR(8) NOT NULL,
    status VARCHAR(24) NOT NULL,
    requested_quantity NUMERIC(38, 18) NOT NULL,
    filled_quantity NUMERIC(38, 18) NOT NULL DEFAULT 0,
    average_fill_price NUMERIC(38, 18),
    client_order_id VARCHAR(120),
    exchange_order_id VARCHAR(120),
    version BIGINT NOT NULL DEFAULT 0,
    submitted_at TIMESTAMPTZ,
    terminal_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_oms_order_approved_direction
        FOREIGN KEY (intent_id, side) REFERENCES approved_trade_intent (intent_id, action),
    CONSTRAINT ck_oms_order_id CHECK (order_id ~ '^order_[a-z0-9_-]{4,71}$'),
    CONSTRAINT ck_oms_exchange CHECK (exchange IN ('GATE', 'BYBIT')),
    CONSTRAINT ck_oms_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_oms_status CHECK (status IN (
        'PLANNED', 'SUBMITTED', 'PARTIALLY_FILLED', 'FILLED',
        'CANCELLED', 'REJECTED', 'EXPIRED', 'RECONCILED'
    )),
    CONSTRAINT ck_oms_quantities CHECK (
        requested_quantity > 0
        AND filled_quantity >= 0
        AND filled_quantity <= requested_quantity
    ),
    CONSTRAINT ck_oms_fill_price CHECK (
        (filled_quantity = 0 AND average_fill_price IS NULL)
        OR (filled_quantity > 0 AND average_fill_price > 0)
    ),
    CONSTRAINT ck_oms_version CHECK (version >= 0)
);

CREATE INDEX ix_oms_order_account_created
    ON oms_order (exchange, account_ref, created_at DESC, id DESC);

CREATE INDEX ix_oms_order_status_updated
    ON oms_order (status, updated_at, id)
    WHERE status IN ('PLANNED', 'SUBMITTED', 'PARTIALLY_FILLED');

CREATE TABLE oms_order_event (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id VARCHAR(80) NOT NULL UNIQUE,
    order_id VARCHAR(80) NOT NULL REFERENCES oms_order (order_id),
    sequence BIGINT NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    from_status VARCHAR(24),
    to_status VARCHAR(24) NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_oms_order_event_sequence UNIQUE (order_id, sequence),
    CONSTRAINT ck_oms_order_event_sequence CHECK (sequence > 0),
    CONSTRAINT ck_oms_order_event_payload CHECK (jsonb_typeof(payload) = 'object')
);

--rollback DROP TABLE IF EXISTS oms_order_event;
--rollback DROP TABLE IF EXISTS oms_order;
--rollback DROP TABLE IF EXISTS approved_trade_intent;
--rollback DROP TABLE IF EXISTS trade_proposal;
--rollback DROP TABLE IF EXISTS trade_decision;
--rollback DROP TABLE IF EXISTS outbox_event;
--rollback DROP TABLE IF EXISTS workflow_event;
--rollback DROP TABLE IF EXISTS workflow_run;
