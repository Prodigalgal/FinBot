--liquibase formatted sql

--changeset codex:034-provider-display-names splitStatements:true endDelimiter:;
UPDATE ai_provider_profile
SET display_name = CASE profile_id
        WHEN 'provider_grok_sub2api' THEN 'sub2api-grok'
        WHEN 'provider_gemini_default' THEN 'sub2api-gemini'
        ELSE display_name
    END,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE profile_id IN ('provider_grok_sub2api', 'provider_gemini_default')
  AND display_name IS DISTINCT FROM CASE profile_id
        WHEN 'provider_grok_sub2api' THEN 'sub2api-grok'
        WHEN 'provider_gemini_default' THEN 'sub2api-gemini'
        ELSE display_name
    END;

--rollback UPDATE ai_provider_profile SET display_name = CASE profile_id WHEN 'provider_grok_sub2api' THEN 'Sub2API 扩展通道' WHEN 'provider_gemini_default' THEN 'Gemini Gateway' ELSE display_name END, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE profile_id IN ('provider_grok_sub2api', 'provider_gemini_default');
