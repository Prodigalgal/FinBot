--liquibase formatted sql

--changeset codex:030-segmented-environment-research splitStatements:true endDelimiter:;
ALTER TABLE research_artifact
    ADD CONSTRAINT uq_research_artifact_identity_hash UNIQUE (artifact_id, content_hash);

CREATE TABLE research_case (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id VARCHAR(80) NOT NULL UNIQUE,
    request_summary TEXT NOT NULL,
    trigger_type VARCHAR(24) NOT NULL,
    status VARCHAR(16) NOT NULL,
    evidence_artifact_id VARCHAR(80) REFERENCES research_artifact (artifact_id),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_research_case_id CHECK (case_id ~ '^case_[a-z0-9_-]{4,70}$'),
    CONSTRAINT ck_research_case_trigger CHECK (trigger_type IN ('MANUAL', 'SCHEDULED', 'API', 'RECOVERY')),
    CONSTRAINT ck_research_case_status CHECK (status IN ('PENDING', 'RUNNING', 'PARTIAL', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_research_case_time CHECK (completed_at IS NULL OR completed_at >= created_at)
);

CREATE TABLE research_segment (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    segment_id VARCHAR(80) NOT NULL UNIQUE,
    case_id VARCHAR(80) NOT NULL REFERENCES research_case (case_id) ON DELETE CASCADE,
    segment_type VARCHAR(24) NOT NULL,
    data_plane VARCHAR(16),
    workflow_run_id VARCHAR(80) UNIQUE REFERENCES workflow_run (run_id) ON DELETE RESTRICT,
    depends_on_segment_id VARCHAR(80) REFERENCES research_segment (segment_id),
    evidence_artifact_id VARCHAR(80) REFERENCES research_artifact (artifact_id),
    status VARCHAR(16) NOT NULL,
    error_code VARCHAR(80),
    error_message TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_research_segment_case_type UNIQUE (case_id, segment_type),
    CONSTRAINT ck_research_segment_id CHECK (segment_id ~ '^segment_[a-z0-9_-]{4,67}$'),
    CONSTRAINT ck_research_segment_type CHECK (segment_type IN ('EVIDENCE', 'LIVE_RESEARCH', 'DEMO_AUTOTRADE')),
    CONSTRAINT ck_research_segment_data_plane CHECK (
        (segment_type = 'EVIDENCE' AND data_plane IS NULL)
        OR (segment_type = 'LIVE_RESEARCH' AND data_plane = 'LIVE')
        OR (segment_type = 'DEMO_AUTOTRADE' AND data_plane = 'PAPER')
    ),
    CONSTRAINT ck_research_segment_workflow CHECK (
        (segment_type = 'EVIDENCE' AND workflow_run_id IS NULL)
        OR (segment_type <> 'EVIDENCE' AND workflow_run_id IS NOT NULL)
    ),
    CONSTRAINT ck_research_segment_status CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_research_segment_error CHECK (
        (status = 'FAILED' AND error_code IS NOT NULL AND error_message IS NOT NULL)
        OR (status <> 'FAILED' AND error_code IS NULL AND error_message IS NULL)
    ),
    CONSTRAINT ck_research_segment_time CHECK (
        (started_at IS NULL OR started_at >= created_at)
        AND (completed_at IS NULL OR completed_at >= coalesce(started_at, created_at))
    )
);

CREATE INDEX ix_research_segment_case_status
    ON research_segment (case_id, status, id);

CREATE TABLE workflow_evidence_binding (
    workflow_run_id VARCHAR(80) PRIMARY KEY REFERENCES workflow_run (run_id) ON DELETE CASCADE,
    case_id VARCHAR(80) NOT NULL REFERENCES research_case (case_id) ON DELETE CASCADE,
    artifact_id VARCHAR(80) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    bound_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_workflow_evidence_artifact
        FOREIGN KEY (artifact_id, content_hash)
        REFERENCES research_artifact (artifact_id, content_hash),
    CONSTRAINT ck_workflow_evidence_hash CHECK (content_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_workflow_evidence_case
    ON workflow_evidence_binding (case_id, bound_at DESC);

ALTER TABLE market_candle_fact
    ADD COLUMN environment VARCHAR(16) NOT NULL DEFAULT 'LIVE';
ALTER TABLE market_candle_fact DROP CONSTRAINT uq_market_candle;
ALTER TABLE market_candle_fact
    ADD CONSTRAINT uq_market_candle_environment
        UNIQUE (instrument_id, environment, interval_seconds, open_time),
    ADD CONSTRAINT ck_market_candle_environment CHECK (
        (exchange = 'GATE' AND environment IN ('LIVE', 'TESTNET'))
        OR (exchange = 'BYBIT' AND environment IN ('LIVE', 'DEMO'))
    );

ALTER TABLE market_data_artifact
    ADD COLUMN data_plane VARCHAR(16) NOT NULL DEFAULT 'LIVE',
    ADD CONSTRAINT ck_market_data_artifact_plane CHECK (data_plane IN ('LIVE', 'PAPER'));

ALTER TABLE research_market_scope
    ADD COLUMN environment VARCHAR(16) NOT NULL DEFAULT 'LIVE',
    ADD CONSTRAINT ck_research_scope_environment CHECK (
        (exchange = 'GATE' AND environment IN ('LIVE', 'TESTNET'))
        OR (exchange = 'BYBIT' AND environment IN ('LIVE', 'DEMO'))
    );

ALTER TABLE research_forecast
    ADD COLUMN environment VARCHAR(16) NOT NULL DEFAULT 'LIVE',
    ADD COLUMN shadow_notional_usdt NUMERIC(30, 8) NOT NULL DEFAULT 100,
    ADD COLUMN shadow_pnl_usdt NUMERIC(30, 8),
    ADD CONSTRAINT ck_research_forecast_environment CHECK (
        (exchange = 'GATE' AND environment IN ('LIVE', 'TESTNET'))
        OR (exchange = 'BYBIT' AND environment IN ('LIVE', 'DEMO'))
    ),
    ADD CONSTRAINT ck_research_forecast_shadow_notional CHECK (shadow_notional_usdt > 0);

UPDATE research_forecast
SET shadow_pnl_usdt = CASE direction
    WHEN 'UP' THEN actual_return * shadow_notional_usdt
    WHEN 'DOWN' THEN -actual_return * shadow_notional_usdt
    ELSE NULL
END
WHERE status = 'EVALUATED';

ALTER TABLE research_forecast
    ADD CONSTRAINT ck_research_forecast_shadow_result CHECK (
        (status = 'EVALUATED' AND direction IN ('UP', 'DOWN') AND shadow_pnl_usdt IS NOT NULL)
        OR ((status <> 'EVALUATED' OR direction NOT IN ('UP', 'DOWN')) AND shadow_pnl_usdt IS NULL)
    );

ALTER TABLE exchange_account
    ADD CONSTRAINT uq_exchange_account_execution_environment
        UNIQUE (account_id, exchange, environment);

ALTER TABLE risk_assessment ADD COLUMN environment VARCHAR(16);
UPDATE risk_assessment assessment
SET environment = account.environment
FROM exchange_account account
WHERE account.account_id = assessment.account_id;
ALTER TABLE risk_assessment
    ALTER COLUMN environment SET NOT NULL,
    ADD CONSTRAINT ck_risk_assessment_environment CHECK (environment IN ('TESTNET', 'DEMO')),
    ADD CONSTRAINT fk_risk_assessment_account_environment
        FOREIGN KEY (account_id, exchange, environment)
        REFERENCES exchange_account (account_id, exchange, environment),
    ADD CONSTRAINT uq_risk_assessment_environment_identity
        UNIQUE (assessment_id, account_id, instrument_id, exchange, environment);

ALTER TABLE approved_trade_intent ADD COLUMN environment VARCHAR(16);
UPDATE approved_trade_intent intent
SET environment = assessment.environment
FROM risk_assessment assessment
WHERE assessment.assessment_id = intent.risk_assessment_id;
ALTER TABLE approved_trade_intent
    ALTER COLUMN environment SET NOT NULL,
    ADD CONSTRAINT ck_trade_intent_environment CHECK (environment IN ('TESTNET', 'DEMO')),
    ADD CONSTRAINT fk_trade_intent_risk_environment
        FOREIGN KEY (risk_assessment_id, account_id, instrument_id, exchange, environment)
        REFERENCES risk_assessment (assessment_id, account_id, instrument_id, exchange, environment),
    ADD CONSTRAINT fk_trade_intent_account_environment
        FOREIGN KEY (account_id, exchange, environment)
        REFERENCES exchange_account (account_id, exchange, environment),
    ADD CONSTRAINT uq_trade_intent_environment_identity
        UNIQUE (intent_id, account_id, instrument_id, exchange, environment);

ALTER TABLE oms_order
    ADD CONSTRAINT fk_oms_order_intent_environment
        FOREIGN KEY (intent_id, account_ref, instrument_id, exchange, environment)
        REFERENCES approved_trade_intent (intent_id, account_id, instrument_id, exchange, environment),
    ADD CONSTRAINT fk_oms_order_account_environment
        FOREIGN KEY (account_ref, exchange, environment)
        REFERENCES exchange_account (account_id, exchange, environment);

UPDATE background_task
SET payload = payload || jsonb_build_object(
        'marketEnvironment', coalesce(payload->'marketEnvironment', 'null'::jsonb),
        'demoWorkflowVersionId', coalesce(payload->'demoWorkflowVersionId', 'null'::jsonb)
    )
WHERE task_type = 'INSTANT_RESEARCH';

INSERT INTO workflow_definition_version (
    version_id, definition_id, version_number, status, default_debate_rounds,
    maximum_steps, maximum_duration_seconds, maximum_tokens, maximum_cost_usd,
    failure_policy, checksum, published_at, created_by
)
SELECT
    'workflowversion_standard_v5', definition_id, 5, 'DRAFT', default_debate_rounds,
    maximum_steps, maximum_duration_seconds, maximum_tokens, maximum_cost_usd,
    failure_policy, 'da9cc98ab43670d675bc330d60e7ddf182d2dac266012306db1745280d05a59b',
    NULL, 'system-migration'
FROM workflow_definition_version
WHERE version_id = 'workflowversion_standard_v4';

INSERT INTO workflow_node_definition (
    version_id, node_id, node_type, display_name, role_name, role_template_id,
    provider_profile_id, model_name, reasoning_effort,
    fallback_provider_profile_id, fallback_model_name, fallback_reasoning_effort,
    system_prompt, user_prompt_template, output_contract, context_mode,
    context_history_rounds, context_max_messages, maximum_output_tokens,
    timeout_seconds, retry_max_attempts, retry_backoff_seconds, operation,
    position_x, position_y, enabled
)
SELECT
    'workflowversion_standard_v5', node_id, node_type, display_name, role_name, role_template_id,
    provider_profile_id, model_name, reasoning_effort,
    fallback_provider_profile_id, fallback_model_name, fallback_reasoning_effort,
    system_prompt, user_prompt_template, output_contract, context_mode,
    context_history_rounds, context_max_messages, maximum_output_tokens,
    timeout_seconds, retry_max_attempts, retry_backoff_seconds,
    CASE
        WHEN node_type = 'QUANT' AND operation = 'statistical_analysis'
            THEN 'multi_strategy_ensemble'
        ELSE operation
    END,
    position_x, position_y, enabled
FROM workflow_node_definition
WHERE version_id = 'workflowversion_standard_v4';

INSERT INTO workflow_edge_definition (
    version_id, edge_id, source_node_id, target_node_id, activation_mode, context_mode,
    condition_field, condition_operator, condition_value, loop_edge, maximum_traversals
)
SELECT
    'workflowversion_standard_v5', edge_id, source_node_id, target_node_id,
    activation_mode, context_mode, condition_field, condition_operator, condition_value,
    loop_edge, maximum_traversals
FROM workflow_edge_definition
WHERE version_id = 'workflowversion_standard_v4';

UPDATE workflow_definition_version
SET status = 'ARCHIVED'
WHERE version_id = 'workflowversion_standard_v4';

UPDATE workflow_definition_version
SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
WHERE version_id = 'workflowversion_standard_v5';

--rollback UPDATE workflow_definition_version SET status = 'DRAFT', published_at = NULL WHERE version_id = 'workflowversion_standard_v5';
--rollback UPDATE workflow_definition_version SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP WHERE version_id = 'workflowversion_standard_v4';
--rollback DELETE FROM workflow_edge_definition WHERE version_id = 'workflowversion_standard_v5';
--rollback DELETE FROM workflow_node_definition WHERE version_id = 'workflowversion_standard_v5';
--rollback DELETE FROM workflow_definition_version WHERE version_id = 'workflowversion_standard_v5';
--rollback ALTER TABLE oms_order DROP CONSTRAINT IF EXISTS fk_oms_order_account_environment;
--rollback ALTER TABLE oms_order DROP CONSTRAINT IF EXISTS fk_oms_order_intent_environment;
--rollback ALTER TABLE approved_trade_intent DROP COLUMN IF EXISTS environment;
--rollback ALTER TABLE risk_assessment DROP COLUMN IF EXISTS environment;
--rollback ALTER TABLE exchange_account DROP CONSTRAINT IF EXISTS uq_exchange_account_execution_environment;
--rollback ALTER TABLE research_forecast DROP COLUMN IF EXISTS shadow_pnl_usdt;
--rollback ALTER TABLE research_forecast DROP COLUMN IF EXISTS shadow_notional_usdt;
--rollback ALTER TABLE research_forecast DROP COLUMN IF EXISTS environment;
--rollback ALTER TABLE research_market_scope DROP COLUMN IF EXISTS environment;
--rollback ALTER TABLE market_data_artifact DROP COLUMN IF EXISTS data_plane;
--rollback ALTER TABLE market_candle_fact DROP CONSTRAINT IF EXISTS uq_market_candle_environment;
--rollback ALTER TABLE market_candle_fact DROP COLUMN IF EXISTS environment;
--rollback DROP TABLE IF EXISTS workflow_evidence_binding;
--rollback DROP TABLE IF EXISTS research_segment;
--rollback DROP TABLE IF EXISTS research_case;
--rollback ALTER TABLE research_artifact DROP CONSTRAINT IF EXISTS uq_research_artifact_identity_hash;
