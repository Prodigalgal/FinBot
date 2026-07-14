--liquibase formatted sql

--changeset codex:005-ingestion-research splitStatements:true endDelimiter:;
CREATE TABLE network_proxy_route (
    route_id VARCHAR(80) PRIMARY KEY,
    route_type VARCHAR(32) NOT NULL UNIQUE,
    display_name VARCHAR(120) NOT NULL,
    require_proxy BOOLEAN NOT NULL,
    allow_direct BOOLEAN NOT NULL,
    proxy_url_env VARCHAR(120),
    expected_ip_family VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_network_proxy_route_id CHECK (route_id ~ '^route_[a-z0-9_-]{4,73}$'),
    CONSTRAINT ck_network_proxy_route_type CHECK (route_type IN (
        'FIRECRAWL', 'EXCHANGE_GATE', 'EXCHANGE_BYBIT', 'PUBLIC_DATA'
    )),
    CONSTRAINT ck_network_proxy_route_policy CHECK (NOT (require_proxy AND allow_direct)),
    CONSTRAINT ck_network_proxy_route_env CHECK (
        (require_proxy AND proxy_url_env IS NOT NULL) OR NOT require_proxy
    ),
    CONSTRAINT ck_network_proxy_route_family CHECK (
        expected_ip_family IN ('IPV4', 'IPV6', 'DUAL_STACK', 'UNKNOWN')
    ),
    CONSTRAINT ck_network_proxy_route_version CHECK (version >= 0)
);

CREATE TABLE information_source (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id VARCHAR(80) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    source_mode VARCHAR(40) NOT NULL,
    source_tier VARCHAR(8) NOT NULL,
    category VARCHAR(80) NOT NULL,
    provider VARCHAR(80),
    trust_weight NUMERIC(5, 4) NOT NULL,
    poll_interval_seconds INTEGER NOT NULL,
    priority VARCHAR(8) NOT NULL,
    asset_scope JSONB NOT NULL DEFAULT '[]'::jsonb,
    feed_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
    seed_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
    search_queries JSONB NOT NULL DEFAULT '[]'::jsonb,
    endpoint_base_url TEXT,
    credential_env VARCHAR(120),
    proxy_route_type VARCHAR(32),
    maximum_results INTEGER NOT NULL DEFAULT 5,
    maximum_scrape_targets INTEGER NOT NULL DEFAULT 3,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_information_source_id CHECK (source_id ~ '^source_[a-z0-9_-]{4,72}$'),
    CONSTRAINT ck_information_source_mode CHECK (source_mode IN (
        'RSS', 'FIRECRAWL_SCRAPE', 'FIRECRAWL_SEARCH',
        'FIRECRAWL_SEARCH_THEN_SCRAPE', 'STRUCTURED_API',
        'EXCHANGE_PUBLIC_API'
    )),
    CONSTRAINT ck_information_source_tier CHECK (source_tier IN ('T0', 'T1', 'T2', 'T3', 'T4', 'T5')),
    CONSTRAINT ck_information_source_trust CHECK (trust_weight BETWEEN 0 AND 1),
    CONSTRAINT ck_information_source_interval CHECK (poll_interval_seconds BETWEEN 10 AND 2592000),
    CONSTRAINT ck_information_source_priority CHECK (priority IN ('P0', 'P1', 'P2', 'P3')),
    CONSTRAINT ck_information_source_arrays CHECK (
        jsonb_typeof(asset_scope) = 'array'
        AND jsonb_typeof(feed_urls) = 'array'
        AND jsonb_typeof(seed_urls) = 'array'
        AND jsonb_typeof(search_queries) = 'array'
    ),
    CONSTRAINT ck_information_source_limits CHECK (
        maximum_results BETWEEN 1 AND 100 AND maximum_scrape_targets BETWEEN 0 AND 20
    ),
    CONSTRAINT ck_information_source_version CHECK (version >= 0)
);

CREATE INDEX ix_information_source_enabled_priority
    ON information_source (enabled, priority, source_tier, id);

CREATE TABLE source_collection_run (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    collection_id VARCHAR(80) NOT NULL UNIQUE,
    workflow_run_id VARCHAR(80) REFERENCES workflow_run (run_id) ON DELETE SET NULL,
    source_id VARCHAR(80) NOT NULL REFERENCES information_source (source_id),
    query TEXT,
    status VARCHAR(24) NOT NULL,
    fetched_count INTEGER NOT NULL DEFAULT 0,
    inserted_count INTEGER NOT NULL DEFAULT 0,
    duplicate_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(80),
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_source_collection_id CHECK (collection_id ~ '^collection_[a-z0-9_-]{4,67}$'),
    CONSTRAINT ck_source_collection_status CHECK (status IN (
        'RUNNING', 'COMPLETED', 'PARTIAL', 'BLOCKED', 'FAILED'
    )),
    CONSTRAINT ck_source_collection_counts CHECK (
        fetched_count >= 0 AND inserted_count >= 0 AND duplicate_count >= 0
        AND inserted_count + duplicate_count <= fetched_count
    ),
    CONSTRAINT ck_source_collection_terminal CHECK (
        (status = 'RUNNING' AND completed_at IS NULL)
        OR (status <> 'RUNNING' AND completed_at IS NOT NULL)
    )
);

CREATE INDEX ix_source_collection_run_workflow
    ON source_collection_run (workflow_run_id, started_at DESC, id DESC);
CREATE INDEX ix_source_collection_run_source
    ON source_collection_run (source_id, started_at DESC, id DESC);

CREATE TABLE raw_evidence (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    evidence_id VARCHAR(80) NOT NULL UNIQUE,
    collection_id VARCHAR(80) NOT NULL REFERENCES source_collection_run (collection_id) ON DELETE CASCADE,
    source_id VARCHAR(80) NOT NULL REFERENCES information_source (source_id),
    requested_url TEXT,
    canonical_url TEXT,
    query TEXT,
    title TEXT,
    status_code INTEGER,
    content_type VARCHAR(160),
    raw_content TEXT NOT NULL,
    response_headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    content_hash CHAR(64) NOT NULL,
    deduplication_key CHAR(64) NOT NULL UNIQUE,
    published_at TIMESTAMPTZ,
    fetched_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_raw_evidence_id CHECK (evidence_id ~ '^evidence_[a-z0-9_-]{4,69}$'),
    CONSTRAINT ck_raw_evidence_status CHECK (status_code IS NULL OR status_code BETWEEN 100 AND 599),
    CONSTRAINT ck_raw_evidence_json CHECK (
        jsonb_typeof(response_headers) = 'object' AND jsonb_typeof(metadata) = 'object'
    ),
    CONSTRAINT ck_raw_evidence_hash CHECK (
        content_hash ~ '^[0-9a-f]{64}$' AND deduplication_key ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX ix_raw_evidence_source_fetched
    ON raw_evidence (source_id, fetched_at DESC, id DESC);
CREATE INDEX ix_raw_evidence_collection
    ON raw_evidence (collection_id, id);

CREATE TABLE normalized_document (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id VARCHAR(80) NOT NULL UNIQUE,
    evidence_id VARCHAR(80) NOT NULL UNIQUE REFERENCES raw_evidence (evidence_id) ON DELETE CASCADE,
    source_id VARCHAR(80) NOT NULL REFERENCES information_source (source_id),
    source_tier VARCHAR(8) NOT NULL,
    category VARCHAR(80) NOT NULL,
    trust_weight NUMERIC(5, 4) NOT NULL,
    canonical_url TEXT,
    title TEXT,
    title_key VARCHAR(500) NOT NULL,
    language VARCHAR(16),
    normalized_text TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    asset_scope JSONB NOT NULL DEFAULT '[]'::jsonb,
    published_at TIMESTAMPTZ,
    fetched_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_normalized_document_id CHECK (document_id ~ '^document_[a-z0-9_-]{4,69}$'),
    CONSTRAINT ck_normalized_document_trust CHECK (trust_weight BETWEEN 0 AND 1),
    CONSTRAINT ck_normalized_document_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_normalized_document_assets CHECK (jsonb_typeof(asset_scope) = 'array')
);

CREATE INDEX ix_normalized_document_recent
    ON normalized_document (fetched_at DESC, id DESC);
CREATE INDEX ix_normalized_document_category
    ON normalized_document (category, fetched_at DESC, id DESC);
CREATE INDEX ix_normalized_document_content_hash
    ON normalized_document (content_hash);

CREATE TABLE research_artifact (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    artifact_id VARCHAR(80) NOT NULL UNIQUE,
    workflow_run_id VARCHAR(80) NOT NULL REFERENCES workflow_run (run_id) ON DELETE CASCADE,
    artifact_type VARCHAR(40) NOT NULL,
    schema_version INTEGER NOT NULL,
    content JSONB NOT NULL,
    provenance JSONB NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_research_artifact_id CHECK (artifact_id ~ '^artifact_[a-z0-9_-]{4,69}$'),
    CONSTRAINT ck_research_artifact_type CHECK (artifact_type IN (
        'EVIDENCE_PACKAGE', 'COMPRESSION_PACKAGE', 'QUANT_RESULT',
        'RISK_ASSESSMENT', 'FINAL_REPORT'
    )),
    CONSTRAINT ck_research_artifact_schema CHECK (schema_version >= 1),
    CONSTRAINT ck_research_artifact_json CHECK (
        jsonb_typeof(content) = 'object' AND jsonb_typeof(provenance) = 'object'
    ),
    CONSTRAINT ck_research_artifact_hash CHECK (content_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_research_artifact_run_type
    ON research_artifact (workflow_run_id, artifact_type, created_at DESC, id DESC);

CREATE TABLE ai_compression (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    compression_id VARCHAR(80) NOT NULL UNIQUE,
    workflow_run_id VARCHAR(80) NOT NULL REFERENCES workflow_run (run_id) ON DELETE CASCADE,
    document_id VARCHAR(80) NOT NULL REFERENCES normalized_document (document_id),
    invocation_id VARCHAR(80) REFERENCES ai_invocation (invocation_id),
    status VARCHAR(16) NOT NULL,
    summary TEXT,
    key_points JSONB NOT NULL DEFAULT '[]'::jsonb,
    risks JSONB NOT NULL DEFAULT '[]'::jsonb,
    missing_evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    prompt_hash CHAR(64) NOT NULL,
    error_code VARCHAR(80),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_ai_compression_input UNIQUE (workflow_run_id, document_id, prompt_hash),
    CONSTRAINT ck_ai_compression_id CHECK (compression_id ~ '^compression_[a-z0-9_-]{4,66}$'),
    CONSTRAINT ck_ai_compression_status CHECK (status IN ('COMPLETED', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_ai_compression_json CHECK (
        jsonb_typeof(key_points) = 'array'
        AND jsonb_typeof(risks) = 'array'
        AND jsonb_typeof(missing_evidence) = 'array'
        AND jsonb_typeof(evidence_refs) = 'array'
    ),
    CONSTRAINT ck_ai_compression_hash CHECK (prompt_hash ~ '^[0-9a-f]{64}$')
);

INSERT INTO network_proxy_route (
    route_id, route_type, display_name, require_proxy, allow_direct,
    proxy_url_env, expected_ip_family
) VALUES
    ('route_firecrawl_default', 'FIRECRAWL', 'Firecrawl IPv4 代理路由', TRUE, FALSE,
     'FINBOT_FIRECRAWL_PROXY_URL', 'IPV4'),
    ('route_gate_default', 'EXCHANGE_GATE', 'Gate TestNet 网络路由', FALSE, TRUE,
     'FINBOT_GATE_PROXY_URL', 'IPV4'),
    ('route_bybit_default', 'EXCHANGE_BYBIT', 'Bybit Demo IPv4 代理路由', TRUE, FALSE,
     'FINBOT_BYBIT_PROXY_URL', 'IPV4'),
    ('route_public_data_default', 'PUBLIC_DATA', '公开数据直连路由', FALSE, TRUE,
     NULL, 'DUAL_STACK');

INSERT INTO information_source (
    source_id, display_name, source_mode, source_tier, category, provider,
    trust_weight, poll_interval_seconds, priority, asset_scope, feed_urls,
    seed_urls, search_queries, endpoint_base_url, proxy_route_type,
    maximum_results, maximum_scrape_targets, enabled
) VALUES
    ('source_federal_reserve', '美联储官方发布', 'RSS', 'T1', 'macro', 'federal_reserve',
     0.95, 900, 'P0', '["NAS100","XAUUSD","BTCUSDT","DXY"]',
     '["https://www.federalreserve.gov/feeds/press_all.xml"]', '[]', '[]', NULL,
     'PUBLIC_DATA', 10, 0, TRUE),
    ('source_ecb_official', '欧洲央行官方发布', 'RSS', 'T1', 'macro', 'ecb',
     0.95, 900, 'P0', '["DXY","EURUSD","NAS100","XAUUSD"]',
     '["https://www.ecb.europa.eu/rss/press.html","https://www.ecb.europa.eu/rss/speeches.html"]',
     '[]', '[]', NULL, 'PUBLIC_DATA', 10, 0, TRUE),
    ('source_eia_weekly', 'EIA 每周石油报告', 'FIRECRAWL_SCRAPE', 'T1', 'energy', 'eia',
     0.95, 1800, 'P0', '["XTIUSD","USOIL","BZUSDT"]', '[]',
     '["https://www.eia.gov/petroleum/supply/weekly/"]', '[]',
     'https://api.firecrawl.dev/v2', 'FIRECRAWL', 5, 1, TRUE),
    ('source_opec_news', 'OPEC 官方新闻', 'FIRECRAWL_SCRAPE', 'T1', 'energy', 'opec',
     0.95, 1800, 'P0', '["XTIUSD","USOIL","BZUSDT"]', '[]',
     '["https://www.opec.org/opec_web/en/press_room/28.htm"]', '[]',
     'https://api.firecrawl.dev/v2', 'FIRECRAWL', 5, 2, TRUE),
    ('source_white_house', '白宫简报', 'FIRECRAWL_SCRAPE', 'T1', 'geopolitics', 'white_house',
     0.90, 900, 'P0', '["XTIUSD","XAUUSD","NAS100","BTCUSDT"]', '[]',
     '["https://www.whitehouse.gov/briefing-room/"]', '[]',
     'https://api.firecrawl.dev/v2', 'FIRECRAWL', 10, 3, TRUE),
    ('source_reuters_search', 'Reuters 市场新闻发现', 'FIRECRAWL_SEARCH_THEN_SCRAPE',
     'T2', 'broad_market_news', 'reuters', 0.82, 600, 'P1',
     '["XTIUSD","XAUUSD","NAS100","BTCUSDT"]', '[]', '[]',
     '["site:reuters.com oil crude sanctions OPEC latest","site:reuters.com Nasdaq Fed yields earnings latest","site:reuters.com bitcoin ETF SEC regulation latest"]',
     'https://api.firecrawl.dev/v2', 'FIRECRAWL', 10, 5, TRUE),
    ('source_ap_search', 'AP 宏观与地缘新闻发现', 'FIRECRAWL_SEARCH_THEN_SCRAPE',
     'T2', 'broad_market_news', 'ap', 0.78, 600, 'P1',
     '["XTIUSD","XAUUSD","NAS100","BTCUSDT"]', '[]', '[]',
     '["site:apnews.com oil Middle East sanctions latest","site:apnews.com Federal Reserve inflation markets latest"]',
     'https://api.firecrawl.dev/v2', 'FIRECRAWL', 10, 5, TRUE),
    ('source_gate_announcements', 'Gate 公告', 'FIRECRAWL_SCRAPE', 'T1',
     'exchange_announcements', 'gate', 0.85, 600, 'P1',
     '["BTCUSDT","ETHUSDT","SOLUSDT"]', '[]',
     '["https://www.gate.com/announcements"]', '[]',
     'https://api.firecrawl.dev/v2', 'FIRECRAWL', 10, 3, TRUE),
    ('source_bybit_announcements', 'Bybit 公告', 'FIRECRAWL_SCRAPE', 'T1',
     'exchange_announcements', 'bybit', 0.85, 600, 'P1',
     '["BTCUSDT","ETHUSDT","SOLUSDT"]', '[]',
     '["https://announcements.bybit.com/"]', '[]',
     'https://api.firecrawl.dev/v2', 'FIRECRAWL', 10, 3, TRUE),
    ('source_global_search', 'Firecrawl 全局发现', 'FIRECRAWL_SEARCH', 'T4',
     'search_discovery', 'firecrawl', 0.45, 600, 'P2',
     '["BTCUSDT","ETHUSDT","SOLUSDT","NAS100","XAUUSD","XTIUSD"]',
     '[]', '[]', '["crypto markets macro commodities latest"]',
     'https://api.firecrawl.dev/v2', 'FIRECRAWL', 10, 0, TRUE);

--rollback DROP TABLE IF EXISTS ai_compression;
--rollback DROP TABLE IF EXISTS research_artifact;
--rollback DROP TABLE IF EXISTS normalized_document;
--rollback DROP TABLE IF EXISTS raw_evidence;
--rollback DROP TABLE IF EXISTS source_collection_run;
--rollback DROP TABLE IF EXISTS information_source;
--rollback DROP TABLE IF EXISTS network_proxy_route;
