--liquibase formatted sql

--changeset codex:060-mimo2api-direct-endpoint splitStatements:true endDelimiter:;
UPDATE ai_provider_profile
SET base_url = 'https://mimo2api-direct.mnnu.eu.org/v1',
    base_url_env = NULL,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE profile_id = 'provider_mimo_default'
  AND (
    base_url IS DISTINCT FROM 'https://mimo2api-direct.mnnu.eu.org/v1'
    OR base_url_env IS NOT NULL
  );

--rollback UPDATE ai_provider_profile SET base_url = 'https://mimo2api.mnnu.eu.org/v1', base_url_env = NULL, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE profile_id = 'provider_mimo_default';
