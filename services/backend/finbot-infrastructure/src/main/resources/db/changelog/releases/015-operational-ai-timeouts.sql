--liquibase formatted sql

--changeset codex:015-operational-ai-timeouts splitStatements:true endDelimiter:;
UPDATE ai_provider_profile
SET request_timeout_seconds = 600,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE profile_id IN ('provider_mimo_default', 'provider_sub2api_default')
  AND request_timeout_seconds < 600;

--rollback UPDATE ai_provider_profile SET request_timeout_seconds = 120, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE profile_id IN ('provider_mimo_default', 'provider_sub2api_default');
