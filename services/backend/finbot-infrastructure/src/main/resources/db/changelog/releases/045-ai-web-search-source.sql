--liquibase formatted sql

--changeset codex:045-ai-web-search-source splitStatements:true endDelimiter:;
ALTER TABLE information_source
    DROP CONSTRAINT ck_information_source_mode;

ALTER TABLE information_source
    ADD CONSTRAINT ck_information_source_mode CHECK (source_mode IN (
        'RSS', 'HTML_DOCUMENT', 'SEARCH_DISCOVERY', 'JSON_API', 'SITEMAP',
        'AI_WEB_SEARCH', 'FIRECRAWL_SCRAPE', 'FIRECRAWL_SEARCH',
        'FIRECRAWL_SEARCH_THEN_SCRAPE', 'EXCHANGE_PUBLIC_API'
    ));

CREATE TABLE information_source_ai_web_search (
    source_id VARCHAR(80) PRIMARY KEY REFERENCES information_source (source_id),
    provider_profile_id VARCHAR(80) NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    reasoning_effort VARCHAR(24) NOT NULL,
    tool_type VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_source_ai_web_search_model
        FOREIGN KEY (provider_profile_id, model_name)
        REFERENCES ai_model_profile (provider_profile_id, model_name),
    CONSTRAINT ck_source_ai_web_search_reasoning CHECK (reasoning_effort IN (
        'PROVIDER_DEFAULT', 'NONE', 'MINIMAL', 'LOW', 'MEDIUM', 'HIGH', 'XHIGH', 'MAX'
    )),
    CONSTRAINT ck_source_ai_web_search_tool CHECK (tool_type IN (
        'WEB_SEARCH', 'GOOGLE_SEARCH'
    ))
);

CREATE INDEX ix_source_ai_web_search_provider
    ON information_source_ai_web_search (provider_profile_id, model_name, source_id);

CREATE TABLE ai_web_search_invocation (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invocation_id VARCHAR(80) NOT NULL UNIQUE,
    source_id VARCHAR(80) NOT NULL REFERENCES information_source (source_id),
    provider_profile_id VARCHAR(80) NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    tool_type VARCHAR(32) NOT NULL,
    query_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    provider_request_id VARCHAR(200),
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    citation_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(80),
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_ai_web_search_invocation_model
        FOREIGN KEY (provider_profile_id, model_name)
        REFERENCES ai_model_profile (provider_profile_id, model_name),
    CONSTRAINT ck_ai_web_search_invocation_id CHECK (
        invocation_id ~ '^aiweb_[a-z0-9_-]{4,73}$'
    ),
    CONSTRAINT ck_ai_web_search_invocation_hash CHECK (
        query_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_ai_web_search_invocation_status CHECK (
        status IN ('RUNNING', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT ck_ai_web_search_invocation_usage CHECK (
        input_tokens >= 0 AND output_tokens >= 0 AND citation_count >= 0
    )
);

CREATE INDEX ix_ai_web_search_invocation_source_time
    ON ai_web_search_invocation (source_id, started_at DESC, id DESC);

--rollback DROP TABLE IF EXISTS ai_web_search_invocation;
--rollback DROP TABLE IF EXISTS information_source_ai_web_search;
--rollback UPDATE information_source SET source_mode = 'SEARCH_DISCOVERY', provider = 'disabled_ai_web_search', endpoint_base_url = NULL, credential_env = NULL, proxy_route_type = 'PUBLIC_DATA', enabled = FALSE, deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP), version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE source_mode = 'AI_WEB_SEARCH';
--rollback ALTER TABLE information_source DROP CONSTRAINT ck_information_source_mode;
--rollback ALTER TABLE information_source ADD CONSTRAINT ck_information_source_mode CHECK (source_mode IN (
--rollback     'RSS', 'HTML_DOCUMENT', 'SEARCH_DISCOVERY', 'JSON_API', 'SITEMAP',
--rollback     'FIRECRAWL_SCRAPE', 'FIRECRAWL_SEARCH',
--rollback     'FIRECRAWL_SEARCH_THEN_SCRAPE', 'EXCHANGE_PUBLIC_API'
--rollback ));
