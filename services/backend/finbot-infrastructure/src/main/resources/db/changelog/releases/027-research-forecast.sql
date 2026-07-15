--liquibase formatted sql

--changeset codex:027-research-forecast splitStatements:true endDelimiter:;
CREATE TABLE research_market_scope (
    workflow_run_id VARCHAR(80) PRIMARY KEY REFERENCES workflow_run (run_id) ON DELETE CASCADE,
    instrument_id VARCHAR(80) NOT NULL,
    exchange VARCHAR(16) NOT NULL,
    symbol VARCHAR(48) NOT NULL,
    interval_seconds INTEGER NOT NULL,
    forecast_horizon_seconds INTEGER NOT NULL,
    market_reference_price NUMERIC(38, 18) NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_research_scope_instrument_exchange
        FOREIGN KEY (instrument_id, exchange) REFERENCES venue_instrument (instrument_id, exchange),
    CONSTRAINT ck_research_scope_symbol CHECK (symbol ~ '^[A-Z0-9_-]{2,48}$'),
    CONSTRAINT ck_research_scope_interval CHECK (interval_seconds BETWEEN 60 AND 604800),
    CONSTRAINT ck_research_scope_horizon CHECK (
        forecast_horizon_seconds >= interval_seconds
        AND forecast_horizon_seconds <= 31536000
    ),
    CONSTRAINT ck_research_scope_reference CHECK (market_reference_price > 0)
);

ALTER TABLE agent_message
    ADD COLUMN forecast JSONB,
    ADD CONSTRAINT ck_agent_message_forecast CHECK (
        forecast IS NULL OR (message_type = 'CHAIR_VERDICT' AND jsonb_typeof(forecast) = 'object')
    );

CREATE TABLE research_forecast (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    forecast_id VARCHAR(80) NOT NULL UNIQUE,
    workflow_run_id VARCHAR(80) NOT NULL UNIQUE REFERENCES workflow_run (run_id) ON DELETE CASCADE,
    message_id VARCHAR(80) NOT NULL UNIQUE REFERENCES agent_message (message_id) ON DELETE CASCADE,
    instrument_id VARCHAR(80) NOT NULL,
    exchange VARCHAR(16) NOT NULL,
    symbol VARCHAR(48) NOT NULL,
    interval_seconds INTEGER NOT NULL,
    horizon_seconds INTEGER NOT NULL,
    market_reference_price NUMERIC(38, 18) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    reference_price NUMERIC(38, 18),
    expected_low NUMERIC(38, 18),
    expected_high NUMERIC(38, 18),
    invalidation_price NUMERIC(38, 18),
    confidence NUMERIC(5, 4) NOT NULL,
    thesis TEXT NOT NULL,
    evidence_refs JSONB NOT NULL,
    status VARCHAR(16) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    target_at TIMESTAMPTZ NOT NULL,
    actual_price NUMERIC(38, 18),
    actual_return NUMERIC(20, 10),
    direction_correct BOOLEAN,
    range_hit BOOLEAN,
    evaluated_at TIMESTAMPTZ,
    CONSTRAINT ck_research_forecast_id CHECK (forecast_id ~ '^forecast_[a-z0-9_-]{4,68}$'),
    CONSTRAINT fk_research_forecast_instrument_exchange
        FOREIGN KEY (instrument_id, exchange) REFERENCES venue_instrument (instrument_id, exchange),
    CONSTRAINT ck_research_forecast_symbol CHECK (symbol ~ '^[A-Z0-9_-]{2,48}$'),
    CONSTRAINT ck_research_forecast_direction CHECK (direction IN ('UP', 'DOWN', 'SIDEWAYS', 'UNCERTAIN')),
    CONSTRAINT ck_research_forecast_status CHECK (status IN ('PENDING', 'EVALUATED', 'UNVERIFIABLE')),
    CONSTRAINT ck_research_forecast_confidence CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_research_forecast_market_reference CHECK (market_reference_price > 0),
    CONSTRAINT ck_research_forecast_evidence CHECK (jsonb_typeof(evidence_refs) = 'array'),
    CONSTRAINT ck_research_forecast_time CHECK (target_at > issued_at),
    CONSTRAINT ck_research_forecast_shape CHECK (
        (direction = 'UNCERTAIN' AND reference_price IS NULL AND expected_low IS NULL
            AND expected_high IS NULL AND invalidation_price IS NULL)
        OR (direction <> 'UNCERTAIN' AND reference_price > 0 AND expected_low > 0
            AND expected_high >= expected_low AND (invalidation_price IS NULL OR invalidation_price > 0))
    ),
    CONSTRAINT ck_research_forecast_evaluation CHECK (
        (status = 'PENDING' AND actual_price IS NULL AND actual_return IS NULL
            AND direction_correct IS NULL AND range_hit IS NULL AND evaluated_at IS NULL)
        OR (status = 'EVALUATED' AND actual_price > 0 AND actual_return IS NOT NULL
            AND evaluated_at IS NOT NULL
            AND ((direction = 'UNCERTAIN' AND direction_correct IS NULL AND range_hit IS NULL)
                OR (direction <> 'UNCERTAIN' AND direction_correct IS NOT NULL AND range_hit IS NOT NULL)))
        OR (status = 'UNVERIFIABLE' AND actual_price IS NULL AND actual_return IS NULL
            AND direction_correct IS NULL AND range_hit IS NULL AND evaluated_at IS NOT NULL)
    )
);

CREATE INDEX ix_research_forecast_due
    ON research_forecast (target_at, id) WHERE status = 'PENDING';
CREATE INDEX ix_research_forecast_instrument
    ON research_forecast (instrument_id, issued_at DESC, id DESC);

ALTER TABLE background_task DROP CONSTRAINT ck_background_task_type;
ALTER TABLE background_task ADD CONSTRAINT ck_background_task_type CHECK (task_type IN (
    'SCHEDULED_RESEARCH', 'INSTANT_RESEARCH', 'ACCOUNT_SYNC',
    'ORDER_RECONCILIATION', 'MARKET_DATA_SYNC', 'INGESTION',
    'CATALOG_SYNC', 'FORECAST_EVALUATION'
));

ALTER TABLE schedule_definition DROP CONSTRAINT ck_schedule_definition_task_type;
ALTER TABLE schedule_definition ADD CONSTRAINT ck_schedule_definition_task_type CHECK (task_type IN (
    'SCHEDULED_RESEARCH', 'ACCOUNT_SYNC', 'ORDER_RECONCILIATION',
    'MARKET_DATA_SYNC', 'INGESTION', 'CATALOG_SYNC', 'FORECAST_EVALUATION'
));

INSERT INTO schedule_definition (
    schedule_id, display_name, task_type, payload, enabled, interval_seconds,
    priority, maximum_attempts, next_run_at
) VALUES (
    'schedule_forecast_evaluation', '到期走势预测实盘验证', 'FORECAST_EVALUATION',
    '{"limit":100}'::jsonb, TRUE, 300, 65, 5, CURRENT_TIMESTAMP
) ON CONFLICT (schedule_id) DO NOTHING;

UPDATE background_task
SET payload = payload || jsonb_build_object(
        'marketInstrumentId', coalesce(payload->'marketInstrumentId', 'null'::jsonb),
        'marketSymbol', coalesce(payload->'marketSymbol', 'null'::jsonb),
        'marketExchange', coalesce(payload->'marketExchange', 'null'::jsonb),
        'marketIntervalSeconds', coalesce(payload->'marketIntervalSeconds', 'null'::jsonb),
        'forecastHorizonSeconds', coalesce(payload->'forecastHorizonSeconds', 'null'::jsonb)
    )
WHERE task_type = 'INSTANT_RESEARCH';

UPDATE background_task task
SET payload = jsonb_set(
        task.payload,
        '{marketInstrumentId}',
        to_jsonb(coalesce((
            select instrument.instrument_id
            from venue_instrument instrument
            where instrument.exchange = task.payload->>'marketExchange'
              and instrument.symbol = task.payload->>'marketSymbol'
            order by instrument.execution_enabled desc, instrument.instrument_id
            limit 1
        ), 'instrument_unresolved_legacy')),
        true)
WHERE task.task_type = 'INSTANT_RESEARCH'
  AND task.payload->>'marketSymbol' IS NOT NULL
  AND task.payload->>'marketInstrumentId' IS NULL;

UPDATE background_task
SET payload = jsonb_set(
        payload,
        '{forecastHorizonSeconds}',
        to_jsonb(greatest(coalesce((payload->>'marketIntervalSeconds')::integer, 86400), 86400)),
        true)
WHERE task_type = 'INSTANT_RESEARCH'
  AND jsonb_exists(payload, 'marketSymbol')
  AND payload->>'marketSymbol' IS NOT NULL
  AND payload->>'forecastHorizonSeconds' IS NULL;

--rollback DROP INDEX IF EXISTS ix_research_forecast_instrument;
--rollback DROP INDEX IF EXISTS ix_research_forecast_due;
--rollback DELETE FROM schedule_definition WHERE schedule_id = 'schedule_forecast_evaluation';
--rollback DROP TABLE IF EXISTS research_forecast;
--rollback ALTER TABLE agent_message DROP COLUMN IF EXISTS forecast;
--rollback DROP TABLE IF EXISTS research_market_scope;
