--liquibase formatted sql

--changeset codex:026-product-catalog-and-execution-isolation splitStatements:true endDelimiter:;
CREATE TABLE product_catalog_sync_run (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sync_run_id VARCHAR(80) NOT NULL UNIQUE,
    exchange VARCHAR(16) NOT NULL,
    market_type VARCHAR(24) NOT NULL,
    status VARCHAR(16) NOT NULL,
    discovered_count INTEGER NOT NULL DEFAULT 0,
    active_count INTEGER NOT NULL DEFAULT 0,
    inactive_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(80),
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_catalog_sync_run_id CHECK (sync_run_id ~ '^catalogsync_[a-z0-9_-]{4,67}$'),
    CONSTRAINT ck_catalog_sync_exchange CHECK (exchange IN ('GATE', 'BYBIT')),
    CONSTRAINT ck_catalog_sync_market CHECK (market_type IN ('SPOT', 'LINEAR_PERPETUAL')),
    CONSTRAINT ck_catalog_sync_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_catalog_sync_counts CHECK (
        discovered_count >= 0 AND active_count >= 0 AND inactive_count >= 0
        AND active_count + inactive_count <= discovered_count
    ),
    CONSTRAINT ck_catalog_sync_terminal CHECK (
        (status = 'RUNNING' AND completed_at IS NULL)
        OR (status <> 'RUNNING' AND completed_at IS NOT NULL)
    )
);

CREATE INDEX ix_product_catalog_sync_scope
    ON product_catalog_sync_run (exchange, market_type, started_at DESC, id DESC);

CREATE TABLE instrument_quote_snapshot (
    instrument_id VARCHAR(80) PRIMARY KEY REFERENCES venue_instrument (instrument_id) ON DELETE CASCADE,
    last_price NUMERIC(38, 18) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_instrument_quote_price CHECK (last_price > 0),
    CONSTRAINT ck_instrument_quote_time CHECK (updated_at >= observed_at)
);

ALTER TABLE background_task DROP CONSTRAINT ck_background_task_type;
ALTER TABLE background_task ADD CONSTRAINT ck_background_task_type CHECK (task_type IN (
    'SCHEDULED_RESEARCH', 'INSTANT_RESEARCH', 'ACCOUNT_SYNC',
    'ORDER_RECONCILIATION', 'MARKET_DATA_SYNC', 'INGESTION', 'CATALOG_SYNC'
));

ALTER TABLE schedule_definition DROP CONSTRAINT ck_schedule_definition_task_type;
ALTER TABLE schedule_definition ADD CONSTRAINT ck_schedule_definition_task_type CHECK (task_type IN (
    'SCHEDULED_RESEARCH', 'ACCOUNT_SYNC', 'ORDER_RECONCILIATION',
    'MARKET_DATA_SYNC', 'INGESTION', 'CATALOG_SYNC'
));

INSERT INTO schedule_definition (
    schedule_id, display_name, task_type, payload, enabled, interval_seconds,
    priority, maximum_attempts, next_run_at
) VALUES
    ('schedule_catalog_gate_spot', 'Gate 现货产品目录同步', 'CATALOG_SYNC',
     '{"exchange":"GATE","marketType":"SPOT"}'::jsonb, TRUE, 21600, 45, 5, CURRENT_TIMESTAMP),
    ('schedule_catalog_gate_linear', 'Gate USDT 永续产品目录同步', 'CATALOG_SYNC',
     '{"exchange":"GATE","marketType":"LINEAR_PERPETUAL"}'::jsonb, TRUE, 21600, 45, 5, CURRENT_TIMESTAMP),
    ('schedule_catalog_bybit_spot', 'Bybit 现货产品目录同步', 'CATALOG_SYNC',
     '{"exchange":"BYBIT","marketType":"SPOT"}'::jsonb, TRUE, 21600, 45, 5, CURRENT_TIMESTAMP),
    ('schedule_catalog_bybit_linear', 'Bybit USDT 永续产品目录同步', 'CATALOG_SYNC',
     '{"exchange":"BYBIT","marketType":"LINEAR_PERPETUAL"}'::jsonb, TRUE, 21600, 45, 5, CURRENT_TIMESTAMP)
ON CONFLICT (schedule_id) DO NOTHING;

ALTER TABLE exchange_account
    ADD CONSTRAINT uq_exchange_account_id_exchange UNIQUE (account_id, exchange);
ALTER TABLE venue_instrument
    ADD CONSTRAINT uq_venue_instrument_id_exchange UNIQUE (instrument_id, exchange);

ALTER TABLE risk_assessment
    ADD COLUMN instrument_id VARCHAR(80),
    ADD COLUMN exchange VARCHAR(16);

WITH ranked_candidates AS (
    SELECT assessment.assessment_id,
           instrument.instrument_id,
           instrument.exchange,
           row_number() OVER (
               PARTITION BY assessment.assessment_id
               ORDER BY instrument.execution_enabled DESC,
                        (instrument.status = 'ACTIVE') DESC,
                        instrument.instrument_id
           ) AS candidate_rank
    FROM risk_assessment assessment
    JOIN trade_proposal proposal ON proposal.proposal_id = assessment.proposal_id
    JOIN exchange_account account ON account.account_id = assessment.account_id
    JOIN venue_instrument instrument
      ON instrument.exchange = account.exchange
     AND replace(replace(upper(instrument.symbol), '_', ''), '-', '') =
         replace(replace(upper(proposal.symbol), '_', ''), '-', '')
)
UPDATE risk_assessment assessment
SET instrument_id = candidate.instrument_id,
    exchange = candidate.exchange
FROM ranked_candidates candidate
WHERE candidate.assessment_id = assessment.assessment_id
  AND candidate.candidate_rank = 1;

ALTER TABLE risk_assessment
    ALTER COLUMN instrument_id SET NOT NULL,
    ALTER COLUMN exchange SET NOT NULL,
    ADD CONSTRAINT fk_risk_assessment_account_exchange
        FOREIGN KEY (account_id, exchange) REFERENCES exchange_account (account_id, exchange),
    ADD CONSTRAINT fk_risk_assessment_instrument_exchange
        FOREIGN KEY (instrument_id, exchange) REFERENCES venue_instrument (instrument_id, exchange),
    ADD CONSTRAINT uq_risk_assessment_execution_identity
        UNIQUE (assessment_id, account_id, instrument_id, exchange);

ALTER TABLE approved_trade_intent
    ADD COLUMN instrument_id VARCHAR(80),
    ADD COLUMN exchange VARCHAR(16);

UPDATE approved_trade_intent intent
SET instrument_id = assessment.instrument_id,
    exchange = assessment.exchange
FROM risk_assessment assessment
WHERE assessment.assessment_id = intent.risk_assessment_id;

ALTER TABLE approved_trade_intent
    ALTER COLUMN instrument_id SET NOT NULL,
    ALTER COLUMN exchange SET NOT NULL,
    ADD CONSTRAINT fk_trade_intent_execution_assessment
        FOREIGN KEY (risk_assessment_id, account_id, instrument_id, exchange)
        REFERENCES risk_assessment (assessment_id, account_id, instrument_id, exchange),
    ADD CONSTRAINT fk_trade_intent_account_exchange
        FOREIGN KEY (account_id, exchange) REFERENCES exchange_account (account_id, exchange),
    ADD CONSTRAINT fk_trade_intent_instrument_exchange
        FOREIGN KEY (instrument_id, exchange) REFERENCES venue_instrument (instrument_id, exchange),
    ADD CONSTRAINT uq_trade_intent_execution_identity
        UNIQUE (intent_id, account_id, instrument_id, exchange);

ALTER TABLE oms_order ADD COLUMN instrument_id VARCHAR(80);

UPDATE oms_order orders
SET instrument_id = intent.instrument_id
FROM approved_trade_intent intent
WHERE intent.intent_id = orders.intent_id;

ALTER TABLE oms_order
    ALTER COLUMN instrument_id SET NOT NULL,
    ADD CONSTRAINT fk_oms_order_execution_intent
        FOREIGN KEY (intent_id, account_ref, instrument_id, exchange)
        REFERENCES approved_trade_intent (intent_id, account_id, instrument_id, exchange),
    ADD CONSTRAINT fk_oms_order_account_exchange
        FOREIGN KEY (account_ref, exchange) REFERENCES exchange_account (account_id, exchange),
    ADD CONSTRAINT fk_oms_order_instrument_exchange
        FOREIGN KEY (instrument_id, exchange) REFERENCES venue_instrument (instrument_id, exchange);

CREATE INDEX ix_risk_assessment_instrument
    ON risk_assessment (instrument_id, assessed_at DESC, id DESC);
CREATE INDEX ix_oms_order_instrument_created
    ON oms_order (instrument_id, created_at DESC, id DESC);

--rollback DROP INDEX IF EXISTS ix_oms_order_instrument_created;
--rollback DROP INDEX IF EXISTS ix_risk_assessment_instrument;
--rollback ALTER TABLE oms_order DROP COLUMN IF EXISTS instrument_id;
--rollback ALTER TABLE approved_trade_intent DROP COLUMN IF EXISTS exchange, DROP COLUMN IF EXISTS instrument_id;
--rollback ALTER TABLE risk_assessment DROP COLUMN IF EXISTS exchange, DROP COLUMN IF EXISTS instrument_id;
--rollback DELETE FROM schedule_definition WHERE schedule_id LIKE 'schedule_catalog_%';
--rollback DROP TABLE IF EXISTS instrument_quote_snapshot;
--rollback DROP TABLE IF EXISTS product_catalog_sync_run;
