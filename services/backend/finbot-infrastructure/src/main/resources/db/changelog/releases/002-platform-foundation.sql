--liquibase formatted sql

--changeset codex:002-platform-foundation splitStatements:true endDelimiter:;
CREATE TABLE auth_challenge (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    challenge_id VARCHAR(80) NOT NULL UNIQUE,
    nonce VARCHAR(96) NOT NULL UNIQUE,
    answer_digest CHAR(64) NOT NULL,
    pow_difficulty SMALLINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    failure_count SMALLINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_auth_challenge_id CHECK (challenge_id ~ '^challenge_[a-z0-9_-]{4,66}$'),
    CONSTRAINT ck_auth_challenge_digest CHECK (answer_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_auth_challenge_difficulty CHECK (pow_difficulty BETWEEN 1 AND 8),
    CONSTRAINT ck_auth_challenge_failures CHECK (failure_count BETWEEN 0 AND 10),
    CONSTRAINT ck_auth_challenge_time CHECK (expires_at > created_at AND (consumed_at IS NULL OR consumed_at >= created_at))
);

CREATE INDEX ix_auth_challenge_active
    ON auth_challenge (expires_at, id)
    WHERE consumed_at IS NULL;

CREATE TABLE admin_session (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id VARCHAR(80) NOT NULL UNIQUE,
    token_digest CHAR(64) NOT NULL UNIQUE,
    username VARCHAR(80) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_admin_session_id CHECK (session_id ~ '^session_[a-z0-9_-]{4,68}$'),
    CONSTRAINT ck_admin_session_digest CHECK (token_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_admin_session_time CHECK (
        expires_at > created_at
        AND last_seen_at >= created_at
        AND (revoked_at IS NULL OR revoked_at >= created_at)
    )
);

CREATE INDEX ix_admin_session_active
    ON admin_session (token_digest, expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE system_setting (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    setting_key VARCHAR(120) NOT NULL UNIQUE,
    setting_type VARCHAR(16) NOT NULL,
    value_text TEXT NOT NULL,
    source VARCHAR(16) NOT NULL,
    description VARCHAR(500) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_system_setting_key CHECK (setting_key ~ '^[a-z][a-z0-9_.-]{2,119}$'),
    CONSTRAINT ck_system_setting_type CHECK (setting_type IN ('BOOLEAN', 'INTEGER', 'DECIMAL', 'DURATION', 'TEXT')),
    CONSTRAINT ck_system_setting_source CHECK (source IN ('DEFAULT', 'USER', 'MIGRATED')),
    CONSTRAINT ck_system_setting_version CHECK (version >= 0)
);

CREATE TABLE ai_provider_profile (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    profile_id VARCHAR(80) NOT NULL UNIQUE,
    display_name VARCHAR(120) NOT NULL,
    protocol VARCHAR(16) NOT NULL,
    reasoning_parameter_style VARCHAR(16) NOT NULL DEFAULT 'NONE',
    base_url TEXT,
    base_url_env VARCHAR(120),
    api_key_env VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    connect_timeout_seconds INTEGER NOT NULL DEFAULT 10,
    request_timeout_seconds INTEGER NOT NULL DEFAULT 120,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ai_provider_profile_id CHECK (profile_id ~ '^provider_[a-z0-9_-]{4,71}$'),
    CONSTRAINT ck_ai_provider_protocol CHECK (protocol IN ('CHAT', 'RESPONSES')),
    CONSTRAINT ck_ai_provider_reasoning_style CHECK (reasoning_parameter_style IN ('NONE', 'FLAT', 'NESTED')),
    CONSTRAINT ck_ai_provider_base_url CHECK (
        (base_url IS NOT NULL AND base_url_env IS NULL)
        OR (base_url IS NULL AND base_url_env IS NOT NULL)
    ),
    CONSTRAINT ck_ai_provider_env CHECK (
        api_key_env ~ '^[A-Z][A-Z0-9_]{2,119}$'
        AND (base_url_env IS NULL OR base_url_env ~ '^[A-Z][A-Z0-9_]{2,119}$')
    ),
    CONSTRAINT ck_ai_provider_timeout CHECK (
        connect_timeout_seconds BETWEEN 1 AND 60
        AND request_timeout_seconds BETWEEN 5 AND 1800
    ),
    CONSTRAINT ck_ai_provider_version CHECK (version >= 0)
);

CREATE TABLE ai_model_profile (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    model_profile_id VARCHAR(100) NOT NULL UNIQUE,
    provider_profile_id VARCHAR(80) NOT NULL REFERENCES ai_provider_profile (profile_id),
    model_name VARCHAR(160) NOT NULL,
    default_reasoning_effort VARCHAR(24) NOT NULL,
    input_usd_per_million NUMERIC(18, 8) NOT NULL,
    output_usd_per_million NUMERIC(18, 8) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ai_model_provider_name UNIQUE (provider_profile_id, model_name),
    CONSTRAINT ck_ai_model_reasoning CHECK (default_reasoning_effort IN (
        'PROVIDER_DEFAULT', 'NONE', 'MINIMAL', 'LOW', 'MEDIUM', 'HIGH', 'XHIGH', 'MAX'
    )),
    CONSTRAINT ck_ai_model_rates CHECK (input_usd_per_million >= 0 AND output_usd_per_million >= 0),
    CONSTRAINT ck_ai_model_version CHECK (version >= 0)
);

CREATE TABLE canonical_product (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id VARCHAR(80) NOT NULL UNIQUE,
    base_asset VARCHAR(32) NOT NULL,
    quote_asset VARCHAR(32) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    category VARCHAR(40) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_canonical_product_pair UNIQUE (base_asset, quote_asset, category),
    CONSTRAINT ck_canonical_product_id CHECK (product_id ~ '^product_[a-z0-9_-]{4,68}$'),
    CONSTRAINT ck_canonical_product_asset CHECK (
        base_asset ~ '^[A-Z0-9]{2,32}$' AND quote_asset ~ '^[A-Z0-9]{2,32}$'
    ),
    CONSTRAINT ck_canonical_product_category CHECK (category IN ('CRYPTO', 'COMMODITY', 'INDEX', 'FOREX', 'EQUITY')),
    CONSTRAINT ck_canonical_product_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'DELISTED'))
);

CREATE INDEX ix_canonical_product_search
    ON canonical_product (status, category, base_asset, quote_asset);

CREATE TABLE venue_instrument (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instrument_id VARCHAR(80) NOT NULL UNIQUE,
    product_id VARCHAR(80) NOT NULL REFERENCES canonical_product (product_id),
    exchange VARCHAR(16) NOT NULL,
    market_type VARCHAR(24) NOT NULL,
    symbol VARCHAR(48) NOT NULL,
    settlement_asset VARCHAR(32) NOT NULL,
    contract_size NUMERIC(38, 18) NOT NULL,
    price_tick NUMERIC(38, 18) NOT NULL,
    quantity_step NUMERIC(38, 18) NOT NULL,
    minimum_quantity NUMERIC(38, 18) NOT NULL,
    maximum_leverage NUMERIC(10, 4) NOT NULL,
    status VARCHAR(16) NOT NULL,
    metadata_updated_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_venue_instrument_symbol UNIQUE (exchange, market_type, symbol),
    CONSTRAINT uq_venue_instrument_identity UNIQUE (instrument_id, product_id),
    CONSTRAINT ck_venue_instrument_id CHECK (instrument_id ~ '^instrument_[a-z0-9_-]{4,65}$'),
    CONSTRAINT ck_venue_instrument_exchange CHECK (exchange IN ('GATE', 'BYBIT')),
    CONSTRAINT ck_venue_instrument_market CHECK (market_type IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL', 'FUTURE')),
    CONSTRAINT ck_venue_instrument_symbol CHECK (symbol ~ '^[A-Z0-9_-]{2,48}$'),
    CONSTRAINT ck_venue_instrument_values CHECK (
        contract_size > 0 AND price_tick > 0 AND quantity_step > 0
        AND minimum_quantity > 0 AND maximum_leverage >= 1
    ),
    CONSTRAINT ck_venue_instrument_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'DELISTED'))
);

CREATE INDEX ix_venue_instrument_product
    ON venue_instrument (product_id, status, exchange, market_type);

CREATE TABLE instrument_alias (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instrument_id VARCHAR(80) NOT NULL REFERENCES venue_instrument (instrument_id) ON DELETE CASCADE,
    alias VARCHAR(80) NOT NULL,
    alias_type VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_instrument_alias UNIQUE (alias, alias_type),
    CONSTRAINT ck_instrument_alias_type CHECK (alias_type IN ('DISPLAY', 'PROVIDER', 'LEGACY'))
);

CREATE TABLE watchlist (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    watchlist_id VARCHAR(80) NOT NULL UNIQUE,
    owner_id VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_watchlist_owner_name UNIQUE (owner_id, name),
    CONSTRAINT ck_watchlist_id CHECK (watchlist_id ~ '^watchlist_[a-z0-9_-]{4,66}$'),
    CONSTRAINT ck_watchlist_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_watchlist_default_owner
    ON watchlist (owner_id)
    WHERE is_default;

CREATE TABLE watchlist_item (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    watchlist_id VARCHAR(80) NOT NULL REFERENCES watchlist (watchlist_id) ON DELETE CASCADE,
    product_id VARCHAR(80) NOT NULL REFERENCES canonical_product (product_id),
    preferred_instrument_id VARCHAR(80),
    research_mode VARCHAR(16) NOT NULL,
    note VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_watchlist_item UNIQUE (watchlist_id, product_id),
    CONSTRAINT fk_watchlist_item_preferred_instrument
        FOREIGN KEY (preferred_instrument_id, product_id)
        REFERENCES venue_instrument (instrument_id, product_id),
    CONSTRAINT ck_watchlist_item_mode CHECK (research_mode IN ('MONITOR', 'RESEARCH', 'PINNED'))
);

CREATE INDEX ix_watchlist_item_product
    ON watchlist_item (product_id, research_mode, watchlist_id);

CREATE TABLE exchange_account (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id VARCHAR(80) NOT NULL UNIQUE,
    exchange VARCHAR(16) NOT NULL,
    environment VARCHAR(16) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    api_key_env VARCHAR(120) NOT NULL,
    api_secret_env VARCHAR(120) NOT NULL,
    proxy_route VARCHAR(80) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_exchange_account UNIQUE (exchange, environment, display_name),
    CONSTRAINT ck_exchange_account_id CHECK (account_id ~ '^account_[a-z0-9_-]{4,68}$'),
    CONSTRAINT ck_exchange_account_exchange CHECK (exchange IN ('GATE', 'BYBIT')),
    CONSTRAINT ck_exchange_account_environment CHECK (environment IN ('TESTNET', 'DEMO')),
    CONSTRAINT ck_exchange_account_env CHECK (
        api_key_env ~ '^[A-Z][A-Z0-9_]{2,119}$' AND api_secret_env ~ '^[A-Z][A-Z0-9_]{2,119}$'
    )
);

CREATE TABLE exchange_sync_cursor (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id VARCHAR(80) NOT NULL REFERENCES exchange_account (account_id),
    stream_type VARCHAR(24) NOT NULL,
    cursor_value TEXT NOT NULL,
    watermark_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_exchange_sync_cursor UNIQUE (account_id, stream_type),
    CONSTRAINT ck_exchange_sync_stream CHECK (stream_type IN ('ACCOUNT', 'ORDER', 'FILL', 'POSITION', 'PNL')),
    CONSTRAINT ck_exchange_sync_version CHECK (version >= 0)
);

CREATE TABLE exchange_account_snapshot (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    snapshot_id VARCHAR(80) NOT NULL UNIQUE,
    account_id VARCHAR(80) NOT NULL REFERENCES exchange_account (account_id),
    source_event_id VARCHAR(160) NOT NULL,
    currency VARCHAR(16) NOT NULL,
    equity NUMERIC(38, 18) NOT NULL,
    available_balance NUMERIC(38, 18) NOT NULL,
    margin_balance NUMERIC(38, 18) NOT NULL,
    unrealized_pnl NUMERIC(38, 18) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_account_snapshot_source UNIQUE (account_id, source_event_id),
    CONSTRAINT ck_account_snapshot_id CHECK (snapshot_id ~ '^snapshot_[a-z0-9_-]{4,67}$'),
    CONSTRAINT ck_account_snapshot_values CHECK (equity >= 0 AND available_balance >= 0 AND margin_balance >= 0),
    CONSTRAINT ck_account_snapshot_time CHECK (received_at >= occurred_at)
);

CREATE INDEX ix_account_snapshot_latest
    ON exchange_account_snapshot (account_id, occurred_at DESC, id DESC);

CREATE TABLE exchange_balance_fact (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fact_id VARCHAR(80) NOT NULL UNIQUE,
    account_id VARCHAR(80) NOT NULL REFERENCES exchange_account (account_id),
    source_event_id VARCHAR(160) NOT NULL,
    currency VARCHAR(16) NOT NULL,
    total NUMERIC(38, 18) NOT NULL,
    available NUMERIC(38, 18) NOT NULL,
    change_amount NUMERIC(38, 18),
    reason VARCHAR(40) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_balance_fact_source UNIQUE (account_id, source_event_id),
    CONSTRAINT ck_balance_fact_id CHECK (fact_id ~ '^balance_[a-z0-9_-]{4,68}$'),
    CONSTRAINT ck_balance_fact_reason CHECK (reason IN (
        'DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'TRADE', 'FUNDING',
        'FEE', 'SETTLEMENT', 'ADJUSTMENT', 'SNAPSHOT'
    )),
    CONSTRAINT ck_balance_fact_values CHECK (total >= 0 AND available >= 0),
    CONSTRAINT ck_balance_fact_time CHECK (received_at >= occurred_at)
);

CREATE INDEX ix_balance_fact_account_time
    ON exchange_balance_fact (account_id, occurred_at DESC, id DESC);

CREATE TABLE exchange_order_fact (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fact_id VARCHAR(80) NOT NULL UNIQUE,
    account_id VARCHAR(80) NOT NULL REFERENCES exchange_account (account_id),
    source_event_id VARCHAR(160) NOT NULL,
    exchange_order_id VARCHAR(160) NOT NULL,
    client_order_id VARCHAR(160),
    symbol VARCHAR(48) NOT NULL,
    side VARCHAR(8) NOT NULL,
    order_type VARCHAR(24) NOT NULL,
    status VARCHAR(32) NOT NULL,
    quantity NUMERIC(38, 18) NOT NULL,
    filled_quantity NUMERIC(38, 18) NOT NULL,
    limit_price NUMERIC(38, 18),
    average_fill_price NUMERIC(38, 18),
    reduce_only BOOLEAN NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_order_fact_source UNIQUE (account_id, source_event_id),
    CONSTRAINT ck_order_fact_id CHECK (fact_id ~ '^orderfact_[a-z0-9_-]{4,66}$'),
    CONSTRAINT ck_order_fact_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_order_fact_type CHECK (order_type IN ('MARKET', 'LIMIT', 'STOP', 'TAKE_PROFIT', 'UNKNOWN')),
    CONSTRAINT ck_order_fact_status CHECK (status IN (
        'NEW', 'SUBMITTED', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED',
        'REJECTED', 'EXPIRED', 'UNKNOWN'
    )),
    CONSTRAINT ck_order_fact_values CHECK (
        quantity > 0 AND filled_quantity >= 0 AND filled_quantity <= quantity
        AND (limit_price IS NULL OR limit_price > 0)
        AND (average_fill_price IS NULL OR average_fill_price > 0)
    ),
    CONSTRAINT ck_order_fact_time CHECK (received_at >= occurred_at)
);

CREATE INDEX ix_order_fact_account_time
    ON exchange_order_fact (account_id, occurred_at DESC, id DESC);
CREATE INDEX ix_order_fact_exchange_order
    ON exchange_order_fact (account_id, exchange_order_id, occurred_at DESC, id DESC);

CREATE TABLE exchange_fill_fact (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fact_id VARCHAR(80) NOT NULL UNIQUE,
    account_id VARCHAR(80) NOT NULL REFERENCES exchange_account (account_id),
    source_event_id VARCHAR(160) NOT NULL,
    exchange_fill_id VARCHAR(160) NOT NULL,
    exchange_order_id VARCHAR(160) NOT NULL,
    client_order_id VARCHAR(160),
    symbol VARCHAR(48) NOT NULL,
    side VARCHAR(8) NOT NULL,
    quantity NUMERIC(38, 18) NOT NULL,
    price NUMERIC(38, 18) NOT NULL,
    fee NUMERIC(38, 18) NOT NULL,
    fee_currency VARCHAR(16) NOT NULL,
    realized_pnl NUMERIC(38, 18),
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_fill_fact_source UNIQUE (account_id, source_event_id),
    CONSTRAINT uq_fill_fact_exchange UNIQUE (account_id, exchange_fill_id),
    CONSTRAINT ck_fill_fact_id CHECK (fact_id ~ '^fill_[a-z0-9_-]{4,71}$'),
    CONSTRAINT ck_fill_fact_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_fill_fact_values CHECK (quantity > 0 AND price > 0 AND fee >= 0),
    CONSTRAINT ck_fill_fact_time CHECK (received_at >= occurred_at)
);

CREATE INDEX ix_fill_fact_account_time
    ON exchange_fill_fact (account_id, occurred_at DESC, id DESC);

CREATE TABLE exchange_position_snapshot (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    snapshot_id VARCHAR(80) NOT NULL UNIQUE,
    account_id VARCHAR(80) NOT NULL REFERENCES exchange_account (account_id),
    source_event_id VARCHAR(160) NOT NULL,
    symbol VARCHAR(48) NOT NULL,
    side VARCHAR(8) NOT NULL,
    quantity NUMERIC(38, 18) NOT NULL,
    entry_price NUMERIC(38, 18),
    mark_price NUMERIC(38, 18),
    liquidation_price NUMERIC(38, 18),
    leverage NUMERIC(10, 4) NOT NULL,
    unrealized_pnl NUMERIC(38, 18) NOT NULL,
    margin NUMERIC(38, 18) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_position_snapshot_source UNIQUE (account_id, source_event_id),
    CONSTRAINT ck_position_snapshot_id CHECK (snapshot_id ~ '^position_[a-z0-9_-]{4,67}$'),
    CONSTRAINT ck_position_snapshot_side CHECK (side IN ('LONG', 'SHORT', 'FLAT')),
    CONSTRAINT ck_position_snapshot_values CHECK (
        quantity >= 0 AND leverage >= 1 AND margin >= 0
        AND (entry_price IS NULL OR entry_price > 0)
        AND (mark_price IS NULL OR mark_price > 0)
        AND (liquidation_price IS NULL OR liquidation_price > 0)
    ),
    CONSTRAINT ck_position_snapshot_time CHECK (received_at >= occurred_at)
);

CREATE INDEX ix_position_snapshot_latest
    ON exchange_position_snapshot (account_id, symbol, occurred_at DESC, id DESC);

CREATE TABLE realized_pnl_fact (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fact_id VARCHAR(80) NOT NULL UNIQUE,
    account_id VARCHAR(80) NOT NULL REFERENCES exchange_account (account_id),
    source_event_id VARCHAR(160) NOT NULL,
    symbol VARCHAR(48) NOT NULL,
    amount NUMERIC(38, 18) NOT NULL,
    currency VARCHAR(16) NOT NULL,
    source_type VARCHAR(24) NOT NULL,
    related_order_id VARCHAR(160),
    related_fill_id VARCHAR(160),
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_realized_pnl_source UNIQUE (account_id, source_event_id),
    CONSTRAINT ck_realized_pnl_id CHECK (fact_id ~ '^pnl_[a-z0-9_-]{4,72}$'),
    CONSTRAINT ck_realized_pnl_source_type CHECK (source_type IN ('TRADE', 'FUNDING', 'FEE', 'ADJUSTMENT')),
    CONSTRAINT ck_realized_pnl_time CHECK (received_at >= occurred_at)
);

CREATE INDEX ix_realized_pnl_account_time
    ON realized_pnl_fact (account_id, occurred_at DESC, id DESC);

CREATE TABLE exchange_reconciliation_run (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reconciliation_id VARCHAR(80) NOT NULL UNIQUE,
    account_id VARCHAR(80) NOT NULL REFERENCES exchange_account (account_id),
    status VARCHAR(24) NOT NULL,
    discrepancy_count INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    error_code VARCHAR(80),
    error_message TEXT,
    CONSTRAINT ck_reconciliation_id CHECK (reconciliation_id ~ '^reconcile_[a-z0-9_-]{4,64}$'),
    CONSTRAINT ck_reconciliation_status CHECK (status IN ('RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_reconciliation_count CHECK (discrepancy_count >= 0),
    CONSTRAINT ck_reconciliation_time CHECK (completed_at IS NULL OR completed_at >= started_at)
);

CREATE INDEX ix_reconciliation_account_time
    ON exchange_reconciliation_run (account_id, started_at DESC, id DESC);

INSERT INTO system_setting (setting_key, setting_type, value_text, source, description) VALUES
    ('autonomous.enabled', 'BOOLEAN', 'true', 'DEFAULT', '启用常驻自动研究循环'),
    ('autonomous.interval', 'DURATION', 'PT60M', 'DEFAULT', '定时研究循环间隔'),
    ('autonomous.debate_rounds', 'INTEGER', '3', 'DEFAULT', '多 Agent 实际辩论轮数'),
    ('autonomous.max_candidates', 'INTEGER', '3', 'DEFAULT', '每轮进入深度研究的最大候选数'),
    ('ai.max_tokens_per_run', 'INTEGER', '500000', 'DEFAULT', '单轮 AI Token 硬预算'),
    ('ai.max_cost_usd_per_run', 'DECIMAL', '0.50', 'DEFAULT', '单轮 AI 成本硬预算'),
    ('ai.max_output_tokens_per_call', 'INTEGER', '4096', 'DEFAULT', '单次模型最大输出 Token'),
    ('execution.paper.enabled', 'BOOLEAN', 'true', 'DEFAULT', '启用测试网或 Demo 模拟执行'),
    ('execution.paper.auto_approve', 'BOOLEAN', 'true', 'DEFAULT', '测试网建议通过风险门禁后自动批准'),
    ('execution.live.enabled', 'BOOLEAN', 'false', 'DEFAULT', '真实盘写入永久关闭'),
    ('execution.max_notional_usdt', 'DECIMAL', '100.00', 'DEFAULT', '单笔模拟订单最大名义价值'),
    ('ingestion.firecrawl.proxy_required', 'BOOLEAN', 'true', 'DEFAULT', 'Firecrawl 无代理时禁止直连'),
    ('worker.poll_interval', 'DURATION', 'PT2S', 'DEFAULT', '常驻任务领取间隔'),
    ('worker.lease_duration', 'DURATION', 'PT30S', 'DEFAULT', '任务租约时长'),
    ('worker.heartbeat_interval', 'DURATION', 'PT5S', 'DEFAULT', 'Worker 心跳间隔');

INSERT INTO ai_provider_profile (
    profile_id, display_name, protocol, reasoning_parameter_style,
    base_url, base_url_env, api_key_env, enabled
) VALUES
    ('provider_mimo_default', 'MiMo2API', 'CHAT', 'FLAT', 'https://mimo2api.mnnu.eu.org/v1', NULL, 'FINBOT_MIMO_API_KEY', TRUE),
    ('provider_deepseek_default', 'DeepSeek', 'CHAT', 'NONE', 'https://api.deepseek.com/v1', NULL, 'FINBOT_DEEPSEEK_API_KEY', TRUE),
    ('provider_sub2api_default', 'Sub2API', 'RESPONSES', 'NESTED', NULL, 'FINBOT_SUB2API_BASE_URL', 'FINBOT_SUB2API_API_KEY', TRUE);

INSERT INTO ai_model_profile (
    model_profile_id, provider_profile_id, model_name, default_reasoning_effort,
    input_usd_per_million, output_usd_per_million
) VALUES
    ('model_mimo_v25_pro', 'provider_mimo_default', 'mimo-v2.5-pro', 'HIGH', 0.15000000, 0.60000000),
    ('model_deepseek_default', 'provider_deepseek_default', 'deepseek-chat', 'HIGH', 0.28000000, 0.42000000),
    ('model_gpt56_luna', 'provider_sub2api_default', 'gpt-5.6-luna', 'HIGH', 1.00000000, 4.00000000),
    ('model_gpt56_terra', 'provider_sub2api_default', 'gpt-5.6-terra', 'XHIGH', 5.00000000, 20.00000000),
    ('model_gpt56_sol', 'provider_sub2api_default', 'gpt-5.6-sol', 'MAX', 10.00000000, 40.00000000);

INSERT INTO canonical_product (
    product_id, base_asset, quote_asset, display_name, category, status
) VALUES
    ('product_crypto_btc_usdt', 'BTC', 'USDT', 'Bitcoin / Tether', 'CRYPTO', 'ACTIVE'),
    ('product_crypto_eth_usdt', 'ETH', 'USDT', 'Ethereum / Tether', 'CRYPTO', 'ACTIVE'),
    ('product_crypto_sol_usdt', 'SOL', 'USDT', 'Solana / Tether', 'CRYPTO', 'ACTIVE');

INSERT INTO venue_instrument (
    instrument_id, product_id, exchange, market_type, symbol, settlement_asset,
    contract_size, price_tick, quantity_step, minimum_quantity, maximum_leverage,
    status, metadata_updated_at
) VALUES
    ('instrument_gate_btc_usdt', 'product_crypto_btc_usdt', 'GATE', 'LINEAR_PERPETUAL', 'BTC_USDT', 'USDT', 0.0001, 0.1, 1, 1, 100, 'ACTIVE', CURRENT_TIMESTAMP),
    ('instrument_gate_eth_usdt', 'product_crypto_eth_usdt', 'GATE', 'LINEAR_PERPETUAL', 'ETH_USDT', 'USDT', 0.01, 0.01, 1, 1, 100, 'ACTIVE', CURRENT_TIMESTAMP),
    ('instrument_gate_sol_usdt', 'product_crypto_sol_usdt', 'GATE', 'LINEAR_PERPETUAL', 'SOL_USDT', 'USDT', 1, 0.001, 1, 1, 100, 'ACTIVE', CURRENT_TIMESTAMP),
    ('instrument_bybit_btcusdt', 'product_crypto_btc_usdt', 'BYBIT', 'LINEAR_PERPETUAL', 'BTCUSDT', 'USDT', 1, 0.1, 0.001, 0.001, 100, 'ACTIVE', CURRENT_TIMESTAMP),
    ('instrument_bybit_ethusdt', 'product_crypto_eth_usdt', 'BYBIT', 'LINEAR_PERPETUAL', 'ETHUSDT', 'USDT', 1, 0.01, 0.01, 0.01, 100, 'ACTIVE', CURRENT_TIMESTAMP),
    ('instrument_bybit_solusdt', 'product_crypto_sol_usdt', 'BYBIT', 'LINEAR_PERPETUAL', 'SOLUSDT', 'USDT', 1, 0.001, 0.1, 0.1, 100, 'ACTIVE', CURRENT_TIMESTAMP);

INSERT INTO watchlist (watchlist_id, owner_id, name, description, is_default)
VALUES ('watchlist_admin_default', 'admin', '默认关注', '系统内置的默认研究关注列表', TRUE);

INSERT INTO watchlist_item (
    watchlist_id, product_id, preferred_instrument_id, research_mode, note
) VALUES
    ('watchlist_admin_default', 'product_crypto_btc_usdt', 'instrument_gate_btc_usdt', 'PINNED', '系统默认研究标的'),
    ('watchlist_admin_default', 'product_crypto_eth_usdt', 'instrument_gate_eth_usdt', 'RESEARCH', '系统默认研究标的'),
    ('watchlist_admin_default', 'product_crypto_sol_usdt', 'instrument_gate_sol_usdt', 'RESEARCH', '系统默认研究标的');

INSERT INTO exchange_account (
    account_id, exchange, environment, display_name, api_key_env, api_secret_env, proxy_route, enabled
) VALUES
    ('account_gate_testnet_default', 'GATE', 'TESTNET', 'Gate TestNet', 'FINBOT_GATE_API_KEY', 'FINBOT_GATE_API_SECRET', 'exchange-ipv4', TRUE),
    ('account_bybit_demo_default', 'BYBIT', 'DEMO', 'Bybit Demo', 'FINBOT_BYBIT_API_KEY', 'FINBOT_BYBIT_API_SECRET', 'exchange-ipv4', TRUE);

--rollback DROP TABLE IF EXISTS exchange_reconciliation_run;
--rollback DROP TABLE IF EXISTS realized_pnl_fact;
--rollback DROP TABLE IF EXISTS exchange_position_snapshot;
--rollback DROP TABLE IF EXISTS exchange_fill_fact;
--rollback DROP TABLE IF EXISTS exchange_order_fact;
--rollback DROP TABLE IF EXISTS exchange_balance_fact;
--rollback DROP TABLE IF EXISTS exchange_account_snapshot;
--rollback DROP TABLE IF EXISTS exchange_sync_cursor;
--rollback DROP TABLE IF EXISTS exchange_account;
--rollback DROP TABLE IF EXISTS watchlist_item;
--rollback DROP TABLE IF EXISTS watchlist;
--rollback DROP TABLE IF EXISTS instrument_alias;
--rollback DROP TABLE IF EXISTS venue_instrument;
--rollback DROP TABLE IF EXISTS canonical_product;
--rollback DROP TABLE IF EXISTS ai_model_profile;
--rollback DROP TABLE IF EXISTS ai_provider_profile;
--rollback DROP TABLE IF EXISTS system_setting;
--rollback DROP TABLE IF EXISTS admin_session;
--rollback DROP TABLE IF EXISTS auth_challenge;
