--liquibase formatted sql

--changeset codex:016-configurable-ai-fallback splitStatements:true endDelimiter:;
ALTER TABLE workflow_node_definition
    ADD COLUMN fallback_provider_profile_id VARCHAR(80)
        REFERENCES ai_provider_profile (profile_id),
    ADD COLUMN fallback_model_name VARCHAR(160),
    ADD COLUMN fallback_reasoning_effort VARCHAR(24);

ALTER TABLE workflow_node_definition
    ADD CONSTRAINT ck_workflow_node_fallback_reasoning CHECK (
        fallback_reasoning_effort IS NULL OR fallback_reasoning_effort IN (
            'PROVIDER_DEFAULT', 'NONE', 'MINIMAL', 'LOW', 'MEDIUM', 'HIGH', 'XHIGH', 'MAX'
        )
    ),
    ADD CONSTRAINT ck_workflow_node_fallback_binding CHECK (
        (fallback_provider_profile_id IS NULL
            AND fallback_model_name IS NULL
            AND fallback_reasoning_effort IS NULL)
        OR
        (node_type IN ('COMPRESSOR', 'AGENT', 'AGGREGATOR', 'CHAIR', 'EXECUTION_REVIEW')
            AND fallback_provider_profile_id IS NOT NULL
            AND fallback_model_name IS NOT NULL
            AND fallback_reasoning_effort IS NOT NULL)
    );

ALTER TABLE trade_execution_ai_stage
    ADD COLUMN fallback_provider_profile_id VARCHAR(80)
        REFERENCES ai_provider_profile (profile_id),
    ADD COLUMN fallback_model_name VARCHAR(160),
    ADD COLUMN fallback_reasoning_effort VARCHAR(24),
    ADD COLUMN retry_max_attempts SMALLINT NOT NULL DEFAULT 3,
    ADD COLUMN retry_backoff_seconds INTEGER NOT NULL DEFAULT 2;

ALTER TABLE trade_execution_ai_stage
    DROP CONSTRAINT ck_trade_execution_ai_limits,
    ADD CONSTRAINT ck_trade_execution_ai_limits CHECK (
        maximum_output_tokens BETWEEN 256 AND 65536
        AND timeout_seconds BETWEEN 10 AND 1800
        AND retry_max_attempts BETWEEN 1 AND 5
        AND retry_backoff_seconds BETWEEN 0 AND 300
        AND version >= 0
    ),
    ADD CONSTRAINT ck_trade_execution_ai_fallback_reasoning CHECK (
        fallback_reasoning_effort IS NULL OR fallback_reasoning_effort IN (
            'PROVIDER_DEFAULT', 'NONE', 'MINIMAL', 'LOW', 'MEDIUM', 'HIGH', 'XHIGH', 'MAX'
        )
    ),
    ADD CONSTRAINT ck_trade_execution_ai_fallback_binding CHECK (
        (fallback_provider_profile_id IS NULL
            AND fallback_model_name IS NULL
            AND fallback_reasoning_effort IS NULL)
        OR
        (fallback_provider_profile_id IS NOT NULL
            AND fallback_model_name IS NOT NULL
            AND fallback_reasoning_effort IS NOT NULL)
    );

--rollback ALTER TABLE trade_execution_ai_stage DROP CONSTRAINT IF EXISTS ck_trade_execution_ai_fallback_binding;
--rollback ALTER TABLE trade_execution_ai_stage DROP CONSTRAINT IF EXISTS ck_trade_execution_ai_fallback_reasoning;
--rollback ALTER TABLE trade_execution_ai_stage DROP CONSTRAINT IF EXISTS ck_trade_execution_ai_limits;
--rollback ALTER TABLE trade_execution_ai_stage ADD CONSTRAINT ck_trade_execution_ai_limits CHECK (maximum_output_tokens BETWEEN 256 AND 16384 AND timeout_seconds BETWEEN 10 AND 1800 AND version >= 0);
--rollback ALTER TABLE trade_execution_ai_stage DROP COLUMN IF EXISTS retry_backoff_seconds;
--rollback ALTER TABLE trade_execution_ai_stage DROP COLUMN IF EXISTS retry_max_attempts;
--rollback ALTER TABLE trade_execution_ai_stage DROP COLUMN IF EXISTS fallback_reasoning_effort;
--rollback ALTER TABLE trade_execution_ai_stage DROP COLUMN IF EXISTS fallback_model_name;
--rollback ALTER TABLE trade_execution_ai_stage DROP COLUMN IF EXISTS fallback_provider_profile_id;
--rollback ALTER TABLE workflow_node_definition DROP CONSTRAINT IF EXISTS ck_workflow_node_fallback_binding;
--rollback ALTER TABLE workflow_node_definition DROP CONSTRAINT IF EXISTS ck_workflow_node_fallback_reasoning;
--rollback ALTER TABLE workflow_node_definition DROP COLUMN IF EXISTS fallback_reasoning_effort;
--rollback ALTER TABLE workflow_node_definition DROP COLUMN IF EXISTS fallback_model_name;
--rollback ALTER TABLE workflow_node_definition DROP COLUMN IF EXISTS fallback_provider_profile_id;
