--liquibase formatted sql

--changeset codex:006-market-quant splitStatements:true endDelimiter:;
CREATE TABLE market_candle_fact (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instrument_id VARCHAR(80) NOT NULL REFERENCES venue_instrument (instrument_id),
    exchange VARCHAR(16) NOT NULL,
    symbol VARCHAR(48) NOT NULL,
    interval_seconds INTEGER NOT NULL,
    open_time TIMESTAMPTZ NOT NULL,
    open_price NUMERIC(38, 18) NOT NULL,
    high_price NUMERIC(38, 18) NOT NULL,
    low_price NUMERIC(38, 18) NOT NULL,
    close_price NUMERIC(38, 18) NOT NULL,
    volume NUMERIC(38, 18) NOT NULL,
    turnover NUMERIC(38, 18),
    funding_rate NUMERIC(24, 16) NOT NULL DEFAULT 0,
    source_endpoint TEXT NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_market_candle UNIQUE (instrument_id, interval_seconds, open_time),
    CONSTRAINT ck_market_candle_exchange CHECK (exchange IN ('GATE', 'BYBIT')),
    CONSTRAINT ck_market_candle_interval CHECK (interval_seconds IN (
        60, 180, 300, 900, 1800, 3600, 7200, 14400, 86400, 604800
    )),
    CONSTRAINT ck_market_candle_prices CHECK (
        open_price > 0 AND high_price > 0 AND low_price > 0 AND close_price > 0
        AND high_price >= greatest(open_price, close_price, low_price)
        AND low_price <= least(open_price, close_price, high_price)
    ),
    CONSTRAINT ck_market_candle_volume CHECK (volume >= 0 AND (turnover IS NULL OR turnover >= 0))
);

CREATE INDEX ix_market_candle_instrument_time
    ON market_candle_fact (instrument_id, interval_seconds, open_time DESC, id DESC);

CREATE TABLE market_data_artifact (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    artifact_id VARCHAR(80) NOT NULL UNIQUE,
    workflow_run_id VARCHAR(80) NOT NULL REFERENCES workflow_run (run_id) ON DELETE CASCADE,
    schema_version INTEGER NOT NULL,
    content JSONB NOT NULL,
    payload BYTEA NOT NULL,
    sha256_hex CHAR(64) NOT NULL,
    byte_size BIGINT NOT NULL,
    media_type VARCHAR(120) NOT NULL,
    candle_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_market_data_artifact_id CHECK (artifact_id ~ '^artifact_[a-z0-9_-]{4,69}$'),
    CONSTRAINT ck_market_data_artifact_schema CHECK (schema_version >= 1),
    CONSTRAINT ck_market_data_artifact_content CHECK (jsonb_typeof(content) = 'object'),
    CONSTRAINT ck_market_data_artifact_hash CHECK (sha256_hex ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_market_data_artifact_counts CHECK (byte_size >= 0 AND candle_count >= 0)
);

CREATE INDEX ix_market_data_artifact_run
    ON market_data_artifact (workflow_run_id, created_at DESC, id DESC);

CREATE TABLE quant_research_run (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    research_run_id VARCHAR(80) NOT NULL UNIQUE,
    workflow_run_id VARCHAR(80) NOT NULL REFERENCES workflow_run (run_id) ON DELETE CASCADE,
    input_artifact_id VARCHAR(80) NOT NULL REFERENCES market_data_artifact (artifact_id),
    idempotency_key VARCHAR(120) NOT NULL UNIQUE,
    research_kind VARCHAR(40) NOT NULL,
    strategy_id VARCHAR(120) NOT NULL,
    strategy_version VARCHAR(80) NOT NULL,
    status VARCHAR(24) NOT NULL,
    observation_count BIGINT NOT NULL DEFAULT 0,
    result_fingerprint VARCHAR(200),
    error_code VARCHAR(80),
    error_message TEXT,
    requested_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_quant_research_run_id CHECK (research_run_id ~ '^research_[a-z0-9_-]{4,69}$'),
    CONSTRAINT ck_quant_research_kind CHECK (research_kind IN (
        'BACKTEST', 'PARAMETER_SEARCH', 'PORTFOLIO_OPTIMIZATION',
        'STATISTICAL_ANALYSIS', 'SIGNAL_EVALUATION'
    )),
    CONSTRAINT ck_quant_research_status CHECK (status IN (
        'REQUESTED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT ck_quant_research_count CHECK (observation_count >= 0),
    CONSTRAINT ck_quant_research_time CHECK (
        completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at
    )
);

CREATE INDEX ix_quant_research_run_workflow
    ON quant_research_run (workflow_run_id, requested_at DESC, id DESC);

CREATE TABLE quant_research_event (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id VARCHAR(80) NOT NULL UNIQUE,
    research_run_id VARCHAR(80) NOT NULL REFERENCES quant_research_run (research_run_id) ON DELETE CASCADE,
    sequence BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_quant_research_event_sequence UNIQUE (research_run_id, sequence),
    CONSTRAINT ck_quant_research_event_id CHECK (event_id ~ '^quant_event_[a-f0-9]{32}$'),
    CONSTRAINT ck_quant_research_event_sequence CHECK (sequence > 0),
    CONSTRAINT ck_quant_research_event_type CHECK (event_type IN (
        'research.accepted', 'research.progress', 'research.artifact',
        'research.completed', 'research.failed'
    )),
    CONSTRAINT ck_quant_research_event_payload CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX ix_quant_research_event_run
    ON quant_research_event (research_run_id, sequence);

CREATE TABLE quant_metric_fact (
    research_run_id VARCHAR(80) NOT NULL REFERENCES quant_research_run (research_run_id) ON DELETE CASCADE,
    metric_name VARCHAR(120) NOT NULL,
    metric_value DOUBLE PRECISION NOT NULL,
    metric_unit VARCHAR(32) NOT NULL,
    PRIMARY KEY (research_run_id, metric_name),
    CONSTRAINT ck_quant_metric_finite CHECK (
        metric_value NOT IN ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
    ),
    CONSTRAINT ck_quant_metric_unit CHECK (metric_unit IN (
        'RATIO', 'PERCENT', 'CURRENCY', 'COUNT', 'DURATION_SECONDS'
    ))
);

--rollback DROP TABLE IF EXISTS quant_metric_fact;
--rollback DROP TABLE IF EXISTS quant_research_event;
--rollback DROP TABLE IF EXISTS quant_research_run;
--rollback DROP TABLE IF EXISTS market_data_artifact;
--rollback DROP TABLE IF EXISTS market_candle_fact;
