--liquibase formatted sql

--changeset codex:058-sub2api-direct-endpoints splitStatements:true endDelimiter:;
UPDATE ai_provider_profile
SET base_url = 'https://sub2api-direct.mnnu.eu.org/v1',
    base_url_env = NULL,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE profile_id IN (
    'provider_sub2api_default',
    'provider_grok_sub2api',
    'provider_gemini_default'
)
  AND (
    base_url IS DISTINCT FROM 'https://sub2api-direct.mnnu.eu.org/v1'
    OR base_url_env IS NOT NULL
  );

--rollback UPDATE ai_provider_profile SET base_url = 'https://sub2api.mnnu.eu.org/v1', base_url_env = NULL, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE profile_id IN ('provider_sub2api_default', 'provider_grok_sub2api', 'provider_gemini_default');
