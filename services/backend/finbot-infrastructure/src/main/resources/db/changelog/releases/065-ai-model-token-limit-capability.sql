--liquibase formatted sql

--changeset codex:065-ai-model-token-limit-capability splitStatements:true endDelimiter:;
ALTER TABLE ai_model_profile
    ADD COLUMN token_limit_parameter_style VARCHAR(32) NOT NULL DEFAULT 'PROTOCOL_DEFAULT',
    ADD CONSTRAINT ck_ai_model_token_limit_parameter_style CHECK (
        token_limit_parameter_style IN (
            'PROTOCOL_DEFAULT', 'MAX_TOKENS', 'MAX_COMPLETION_TOKENS', 'MAX_OUTPUT_TOKENS', 'NONE'
        )
    );

UPDATE ai_model_profile
SET token_limit_parameter_style = 'NONE',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE provider_profile_id = 'provider_any2api_research'
  AND model_name IN (
      'deepseek/expert',
      'longcat/longcat-pro',
      'mimo/mimo-v2.5-pro',
      'minmax/MiniMax-M3'
  );

--rollback UPDATE ai_model_profile SET token_limit_parameter_style = 'PROTOCOL_DEFAULT', version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE provider_profile_id = 'provider_any2api_research';
--rollback ALTER TABLE ai_model_profile DROP CONSTRAINT IF EXISTS ck_ai_model_token_limit_parameter_style;
--rollback ALTER TABLE ai_model_profile DROP COLUMN IF EXISTS token_limit_parameter_style;
