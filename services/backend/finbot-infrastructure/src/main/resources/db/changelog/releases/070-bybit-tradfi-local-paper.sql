--liquibase formatted sql

--changeset codex:070-bybit-tradfi-local-paper splitStatements:true endDelimiter:;
CREATE TABLE local_paper_account (
    account_id VARCHAR(80) PRIMARY KEY,
    currency VARCHAR(12) NOT NULL CHECK (currency = 'USDT'),
    initial_balance NUMERIC(38,18) NOT NULL CHECK (initial_balance > 0),
    cash_balance NUMERIC(38,18) NOT NULL,
    fees_usdt NUMERIC(38,18) NOT NULL DEFAULT 0 CHECK (fees_usdt >= 0),
    funding_usdt NUMERIC(38,18) NOT NULL DEFAULT 0,
    orders_enabled BOOLEAN NOT NULL,
    accept_projections_after TIMESTAMPTZ NOT NULL,
    last_checked_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE local_paper_trade (
    trade_id VARCHAR(80) PRIMARY KEY CHECK (trade_id ~ '^paper_[a-z0-9_-]{4,70}$'),
    account_id VARCHAR(80) NOT NULL REFERENCES local_paper_account(account_id),
    projection_id VARCHAR(80) NOT NULL UNIQUE REFERENCES estimated_trade_projection(projection_id),
    instrument_id VARCHAR(80) NOT NULL REFERENCES venue_instrument(instrument_id),
    symbol VARCHAR(48) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING_ENTRY','OPEN','CLOSED','CANCELLED','EXPIRED')),
    state JSONB NOT NULL CHECK (jsonb_typeof(state) = 'object'),
    version BIGINT NOT NULL CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (state->>'tradeId' = trade_id AND state->>'status' = status
           AND state->>'instrumentId' = instrument_id AND state->>'symbol' = symbol)
);

CREATE UNIQUE INDEX uq_local_paper_active_instrument ON local_paper_trade(account_id,instrument_id)
    WHERE status IN ('PENDING_ENTRY','OPEN');
CREATE INDEX ix_local_paper_trade_history ON local_paper_trade(created_at DESC,trade_id DESC);
CREATE INDEX ix_local_paper_trade_active ON local_paper_trade(updated_at,trade_id)
    WHERE status IN ('PENDING_ENTRY','OPEN');

CREATE TABLE local_paper_event (
    event_id VARCHAR(160) PRIMARY KEY,
    trade_id VARCHAR(80) NOT NULL REFERENCES local_paper_trade(trade_id),
    event_type VARCHAR(20) NOT NULL CHECK (event_type IN ('RESERVED','ENTRY_FILL','EXIT_FILL','FUNDING','CANCELLED','EXPIRED')),
    occurred_at TIMESTAMPTZ NOT NULL,
    cash_delta_usdt NUMERIC(38,18) NOT NULL,
    event JSONB NOT NULL CHECK (jsonb_typeof(event) = 'object'),
    recorded_at TIMESTAMPTZ NOT NULL,
    UNIQUE (trade_id,event_type,occurred_at)
);
CREATE INDEX ix_local_paper_event_trade ON local_paper_event(trade_id,occurred_at,event_id);

INSERT INTO local_paper_account(account_id,currency,initial_balance,cash_balance,orders_enabled,
    accept_projections_after,created_at,updated_at)
VALUES ('local_paper_bybit_tradfi','USDT',10000,10000,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);

ALTER TABLE background_task DROP CONSTRAINT ck_background_task_type;
ALTER TABLE background_task ADD CONSTRAINT ck_background_task_type CHECK (task_type IN (
    'SCHEDULED_RESEARCH','INSTANT_RESEARCH','ACCOUNT_SYNC','ORDER_RECONCILIATION',
    'MARKET_DATA_SYNC','INGESTION','CATALOG_SYNC','FORECAST_EVALUATION','LOCAL_PAPER_MATCHING'));
ALTER TABLE schedule_definition DROP CONSTRAINT ck_schedule_definition_task_type;
ALTER TABLE schedule_definition ADD CONSTRAINT ck_schedule_definition_task_type CHECK (task_type IN (
    'SCHEDULED_RESEARCH','ACCOUNT_SYNC','ORDER_RECONCILIATION',
    'MARKET_DATA_SYNC','INGESTION','CATALOG_SYNC','FORECAST_EVALUATION','LOCAL_PAPER_MATCHING'));
INSERT INTO schedule_definition(schedule_id,display_name,task_type,payload,enabled,interval_seconds,
    priority,maximum_attempts,next_run_at)
VALUES ('schedule_local_paper_matching','Bybit TradFi 本地模拟撮合','LOCAL_PAPER_MATCHING',
    '{"limit":50}'::jsonb,TRUE,10,75,3,CURRENT_TIMESTAMP);

--rollback DELETE FROM schedule_definition WHERE schedule_id = 'schedule_local_paper_matching';
--rollback DELETE FROM background_task WHERE task_type = 'LOCAL_PAPER_MATCHING';
--rollback ALTER TABLE schedule_definition DROP CONSTRAINT ck_schedule_definition_task_type;
--rollback ALTER TABLE schedule_definition ADD CONSTRAINT ck_schedule_definition_task_type CHECK (task_type IN ('SCHEDULED_RESEARCH','ACCOUNT_SYNC','ORDER_RECONCILIATION','MARKET_DATA_SYNC','INGESTION','CATALOG_SYNC','FORECAST_EVALUATION'));
--rollback ALTER TABLE background_task DROP CONSTRAINT ck_background_task_type;
--rollback ALTER TABLE background_task ADD CONSTRAINT ck_background_task_type CHECK (task_type IN ('SCHEDULED_RESEARCH','INSTANT_RESEARCH','ACCOUNT_SYNC','ORDER_RECONCILIATION','MARKET_DATA_SYNC','INGESTION','CATALOG_SYNC','FORECAST_EVALUATION'));
--rollback DROP TABLE local_paper_event;
--rollback DROP TABLE local_paper_trade;
--rollback DROP TABLE local_paper_account;
