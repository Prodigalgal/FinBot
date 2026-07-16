--liquibase formatted sql

--changeset codex:032-runtime-configuration-control-plane splitStatements:true endDelimiter:;
CREATE TABLE runtime_secret_override (
    scope_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(80) NOT NULL,
    secret_name VARCHAR(40) NOT NULL,
    ciphertext BYTEA,
    nonce BYTEA,
    fingerprint CHAR(16),
    encryption_key_version SMALLINT,
    version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (scope_type, target_id, secret_name),
    CONSTRAINT ck_runtime_secret_scope CHECK (scope_type IN (
        'AI_PROVIDER', 'EXCHANGE_ACCOUNT', 'PROXY_ROUTE', 'PROXY_GATEWAY', 'INFORMATION_SOURCE'
    )),
    CONSTRAINT ck_runtime_secret_name CHECK (
        (scope_type = 'AI_PROVIDER' AND secret_name = 'API_KEY')
        OR (scope_type = 'EXCHANGE_ACCOUNT' AND secret_name IN ('API_KEY', 'API_SECRET'))
        OR (scope_type = 'PROXY_ROUTE' AND secret_name = 'PROXY_URL')
        OR (scope_type = 'PROXY_GATEWAY' AND secret_name IN ('SUBSCRIPTION_URL', 'INLINE_NODES'))
        OR (scope_type = 'INFORMATION_SOURCE' AND secret_name = 'API_KEY')
    ),
    CONSTRAINT ck_runtime_secret_target CHECK (target_id ~ '^[A-Za-z0-9_-]{2,80}$'),
    CONSTRAINT ck_runtime_secret_payload CHECK (
        (ciphertext IS NULL AND nonce IS NULL AND fingerprint IS NULL
            AND encryption_key_version IS NULL)
        OR (octet_length(ciphertext) >= 24 AND octet_length(nonce) = 12
            AND fingerprint ~ '^[0-9a-f]{16}$' AND encryption_key_version >= 1)
    ),
    CONSTRAINT ck_runtime_secret_version CHECK (version >= 1)
);

CREATE INDEX ix_runtime_secret_override_target
    ON runtime_secret_override (scope_type, target_id);

CREATE TABLE proxy_gateway_profile (
    gateway_id VARCHAR(80) PRIMARY KEY,
    display_name VARCHAR(120) NOT NULL,
    control_url VARCHAR(500) NOT NULL,
    subscription_url_env VARCHAR(120),
    inline_nodes_env VARCHAR(120),
    preferred_names JSONB NOT NULL DEFAULT '[]'::jsonb,
    maximum_nodes INTEGER NOT NULL,
    refresh_seconds INTEGER NOT NULL,
    allow_insecure_tls BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_proxy_gateway_id CHECK (gateway_id ~ '^proxygateway_[a-z0-9_-]{4,67}$'),
    CONSTRAINT ck_proxy_gateway_source CHECK (
        subscription_url_env IS NOT NULL OR inline_nodes_env IS NOT NULL
    ),
    CONSTRAINT ck_proxy_gateway_preferred_names CHECK (jsonb_typeof(preferred_names) = 'array'),
    CONSTRAINT ck_proxy_gateway_maximum_nodes CHECK (maximum_nodes BETWEEN 1 AND 128),
    CONSTRAINT ck_proxy_gateway_refresh CHECK (refresh_seconds BETWEEN 60 AND 86400),
    CONSTRAINT ck_proxy_gateway_version CHECK (version >= 0)
);

INSERT INTO proxy_gateway_profile (
    gateway_id, display_name, control_url, subscription_url_env, inline_nodes_env,
    preferred_names, maximum_nodes, refresh_seconds, allow_insecure_tls
) VALUES
    ('proxygateway_firecrawl', 'Firecrawl 代理池',
     'http://finbot-firecrawl-proxy:8081', 'FINBOT_PROXY_GATEWAY_SECRETS_JSON', NULL,
     '[]'::jsonb, 32, 1800, FALSE),
    ('proxygateway_exchange', '交易所代理池',
     'http://finbot-exchange-proxy:8081', NULL, 'FINBOT_PROXY_GATEWAY_SECRETS_JSON',
     '["JP","OSAKA","SG"]'::jsonb, 16, 1800, FALSE);

CREATE TABLE runtime_secret_audit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scope_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(80) NOT NULL,
    secret_name VARCHAR(40) NOT NULL,
    action VARCHAR(16) NOT NULL,
    fingerprint CHAR(16),
    secret_version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_runtime_secret_audit_scope CHECK (scope_type IN (
        'AI_PROVIDER', 'EXCHANGE_ACCOUNT', 'PROXY_ROUTE', 'PROXY_GATEWAY', 'INFORMATION_SOURCE'
    )),
    CONSTRAINT ck_runtime_secret_audit_action CHECK (action IN ('SET', 'CLEAR')),
    CONSTRAINT ck_runtime_secret_audit_fingerprint CHECK (
        fingerprint IS NULL OR fingerprint ~ '^[0-9a-f]{16}$'
    ),
    CONSTRAINT ck_runtime_secret_audit_version CHECK (secret_version >= 1)
);

CREATE INDEX ix_runtime_secret_audit_target
    ON runtime_secret_audit (scope_type, target_id, occurred_at DESC, id DESC);

ALTER TABLE ai_provider_profile
    ADD COLUMN deleted_at TIMESTAMPTZ;

CREATE INDEX ix_ai_provider_profile_active
    ON ai_provider_profile (display_name, profile_id)
    WHERE deleted_at IS NULL;

UPDATE ai_provider_profile
SET display_name = CASE
        WHEN profile_id = 'provider_grok_sub2api' THEN 'Sub2API 扩展通道'
        ELSE display_name
    END,
    api_key_env = 'FINBOT_AI_PROVIDER_KEYS_JSON',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP;

ALTER TABLE ai_provider_profile
    ALTER COLUMN api_key_env SET DEFAULT 'FINBOT_AI_PROVIDER_KEYS_JSON';

UPDATE exchange_account
SET api_key_env = 'FINBOT_EXCHANGE_ACCOUNT_CREDENTIALS_JSON',
    api_secret_env = 'FINBOT_EXCHANGE_ACCOUNT_CREDENTIALS_JSON',
    updated_at = CURRENT_TIMESTAMP;

ALTER TABLE exchange_account
    ALTER COLUMN api_key_env SET DEFAULT 'FINBOT_EXCHANGE_ACCOUNT_CREDENTIALS_JSON',
    ALTER COLUMN api_secret_env SET DEFAULT 'FINBOT_EXCHANGE_ACCOUNT_CREDENTIALS_JSON';

UPDATE information_source
SET credential_env = 'FINBOT_INFORMATION_SOURCE_KEYS_JSON',
    updated_at = CURRENT_TIMESTAMP
WHERE credential_env IS NOT NULL;

ALTER TABLE information_source
    ALTER COLUMN credential_env SET DEFAULT 'FINBOT_INFORMATION_SOURCE_KEYS_JSON';

UPDATE network_proxy_route
SET proxy_url_env = 'FINBOT_PROXY_ROUTE_URLS_JSON',
    updated_at = CURRENT_TIMESTAMP
WHERE proxy_url_env IS NOT NULL;

ALTER TABLE network_proxy_route
    ALTER COLUMN proxy_url_env SET DEFAULT 'FINBOT_PROXY_ROUTE_URLS_JSON';

--rollback ALTER TABLE network_proxy_route ALTER COLUMN proxy_url_env DROP DEFAULT;
--rollback UPDATE network_proxy_route SET proxy_url_env = CASE route_type WHEN 'FIRECRAWL' THEN 'FINBOT_FIRECRAWL_PROXY_URL' WHEN 'EXCHANGE_GATE' THEN 'FINBOT_GATE_PROXY_URL' WHEN 'EXCHANGE_BYBIT' THEN 'FINBOT_BYBIT_PROXY_URL' ELSE proxy_url_env END, updated_at = CURRENT_TIMESTAMP;
--rollback ALTER TABLE information_source ALTER COLUMN credential_env DROP DEFAULT;
--rollback ALTER TABLE exchange_account ALTER COLUMN api_key_env DROP DEFAULT, ALTER COLUMN api_secret_env DROP DEFAULT;
--rollback UPDATE exchange_account SET api_key_env = CASE exchange WHEN 'GATE' THEN 'FINBOT_GATE_API_KEY' WHEN 'BYBIT' THEN 'FINBOT_BYBIT_API_KEY' ELSE api_key_env END, api_secret_env = CASE exchange WHEN 'GATE' THEN 'FINBOT_GATE_API_SECRET' WHEN 'BYBIT' THEN 'FINBOT_BYBIT_API_SECRET' ELSE api_secret_env END, updated_at = CURRENT_TIMESTAMP;
--rollback ALTER TABLE ai_provider_profile ALTER COLUMN api_key_env DROP DEFAULT;
--rollback UPDATE ai_provider_profile SET display_name = CASE WHEN profile_id = 'provider_grok_sub2api' THEN 'Sub2API Grok' ELSE display_name END, api_key_env = CASE profile_id WHEN 'provider_mimo_default' THEN 'FINBOT_MIMO_API_KEY' WHEN 'provider_deepseek_default' THEN 'FINBOT_DEEPSEEK_API_KEY' WHEN 'provider_sub2api_default' THEN 'FINBOT_SUB2API_API_KEY' WHEN 'provider_gemini_default' THEN 'FINBOT_GEMINI_API_KEY' WHEN 'provider_grok_sub2api' THEN 'FINBOT_GROK_SUB2API_API_KEY' ELSE api_key_env END, version = version + 1, updated_at = CURRENT_TIMESTAMP;
--rollback DROP INDEX IF EXISTS ix_ai_provider_profile_active;
--rollback ALTER TABLE ai_provider_profile DROP COLUMN IF EXISTS deleted_at;
--rollback DROP TABLE IF EXISTS runtime_secret_audit;
--rollback DROP TABLE IF EXISTS proxy_gateway_profile;
--rollback DROP TABLE IF EXISTS runtime_secret_override;
