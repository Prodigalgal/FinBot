--liquibase formatted sql

--changeset codex:056-provider-runtime-capacity splitStatements:true endDelimiter:;
ALTER TABLE ai_provider_profile
    DROP CONSTRAINT ck_ai_provider_timeout;

ALTER TABLE ai_provider_profile
    ADD CONSTRAINT ck_ai_provider_timeout CHECK (
        connect_timeout_seconds BETWEEN 1 AND 60
        AND request_timeout_seconds BETWEEN 5 AND 3600
    );

ALTER TABLE ai_provider_profile
    ADD COLUMN maximum_concurrent_requests SMALLINT NOT NULL DEFAULT 5,
    ADD COLUMN acquire_timeout_seconds INTEGER NOT NULL DEFAULT 1800;

ALTER TABLE ai_provider_profile
    ADD CONSTRAINT ck_ai_provider_runtime_capacity CHECK (
        maximum_concurrent_requests BETWEEN 1 AND 32
        AND acquire_timeout_seconds BETWEEN 5 AND 7200
    );

ALTER TABLE workflow_node_definition
    DROP CONSTRAINT ck_workflow_node_limits,
    ADD CONSTRAINT ck_workflow_node_limits CHECK (
        maximum_output_tokens BETWEEN 64 AND 65536
        AND timeout_seconds BETWEEN 5 AND 3600
        AND retry_max_attempts BETWEEN 1 AND 5
        AND retry_backoff_seconds BETWEEN 0 AND 300
    );

ALTER TABLE trade_execution_ai_stage
    DROP CONSTRAINT ck_trade_execution_ai_limits,
    ADD CONSTRAINT ck_trade_execution_ai_limits CHECK (
        maximum_output_tokens BETWEEN 256 AND 65536
        AND timeout_seconds BETWEEN 10 AND 3600
        AND version >= 0
    );

--rollback ALTER TABLE trade_execution_ai_stage DROP CONSTRAINT IF EXISTS ck_trade_execution_ai_limits;
--rollback ALTER TABLE trade_execution_ai_stage ADD CONSTRAINT ck_trade_execution_ai_limits CHECK (maximum_output_tokens BETWEEN 256 AND 65536 AND timeout_seconds BETWEEN 10 AND 1800 AND version >= 0);
--rollback ALTER TABLE workflow_node_definition DROP CONSTRAINT IF EXISTS ck_workflow_node_limits;
--rollback ALTER TABLE workflow_node_definition ADD CONSTRAINT ck_workflow_node_limits CHECK (maximum_output_tokens BETWEEN 64 AND 65536 AND timeout_seconds BETWEEN 5 AND 1800 AND retry_max_attempts BETWEEN 1 AND 5 AND retry_backoff_seconds BETWEEN 0 AND 300);
--rollback ALTER TABLE ai_provider_profile DROP CONSTRAINT IF EXISTS ck_ai_provider_runtime_capacity;
--rollback ALTER TABLE ai_provider_profile DROP COLUMN IF EXISTS acquire_timeout_seconds;
--rollback ALTER TABLE ai_provider_profile DROP COLUMN IF EXISTS maximum_concurrent_requests;
--rollback UPDATE ai_provider_profile SET request_timeout_seconds = LEAST(request_timeout_seconds, 1800);
--rollback ALTER TABLE ai_provider_profile DROP CONSTRAINT IF EXISTS ck_ai_provider_timeout;
--rollback ALTER TABLE ai_provider_profile ADD CONSTRAINT ck_ai_provider_timeout CHECK (connect_timeout_seconds BETWEEN 1 AND 60 AND request_timeout_seconds BETWEEN 5 AND 1800);
