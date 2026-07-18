--liquibase formatted sql

--changeset codex:036-search-discovery-protocol splitStatements:true endDelimiter:;
ALTER TABLE information_source
    DROP CONSTRAINT ck_information_source_mode;

ALTER TABLE information_source
    ADD CONSTRAINT ck_information_source_mode CHECK (source_mode IN (
        'RSS', 'HTML_DOCUMENT', 'SEARCH_DISCOVERY', 'FIRECRAWL_SCRAPE',
        'FIRECRAWL_SEARCH', 'FIRECRAWL_SEARCH_THEN_SCRAPE', 'STRUCTURED_API',
        'EXCHANGE_PUBLIC_API'
    ));

UPDATE information_source
SET source_mode = 'SEARCH_DISCOVERY',
    endpoint_base_url = NULL,
    provider = 'searxng',
    proxy_route_type = 'WEB_CRAWL',
    enabled = FALSE,
    updated_at = CURRENT_TIMESTAMP
WHERE source_id IN (
    'source_reuters_search', 'source_ap_search', 'source_global_search',
    'source_x_market_search'
);

UPDATE information_source
SET display_name = CASE source_id
        WHEN 'source_reuters_search' THEN 'Reuters 市场新闻发现'
        WHEN 'source_ap_search' THEN 'AP 宏观与地缘新闻发现'
        WHEN 'source_global_search' THEN '通用互联网搜索发现'
        WHEN 'source_x_market_search' THEN 'X 市场公开信息发现'
    END
WHERE source_id IN (
    'source_reuters_search', 'source_ap_search', 'source_global_search',
    'source_x_market_search'
);

--rollback UPDATE information_source
--rollback SET source_mode = 'FIRECRAWL_SEARCH_THEN_SCRAPE',
--rollback     endpoint_base_url = 'https://api.firecrawl.dev/v2',
--rollback     provider = CASE source_id
--rollback         WHEN 'source_reuters_search' THEN 'reuters'
--rollback         WHEN 'source_ap_search' THEN 'ap'
--rollback         WHEN 'source_global_search' THEN 'firecrawl'
--rollback         WHEN 'source_x_market_search' THEN 'x'
--rollback     END,
--rollback     proxy_route_type = 'FIRECRAWL',
--rollback     enabled = TRUE,
--rollback     updated_at = CURRENT_TIMESTAMP
--rollback WHERE source_id IN (
--rollback     'source_reuters_search', 'source_ap_search', 'source_global_search',
--rollback     'source_x_market_search'
--rollback );
--rollback ALTER TABLE information_source DROP CONSTRAINT ck_information_source_mode;
--rollback ALTER TABLE information_source ADD CONSTRAINT ck_information_source_mode CHECK (source_mode IN (
--rollback     'RSS', 'HTML_DOCUMENT', 'FIRECRAWL_SCRAPE', 'FIRECRAWL_SEARCH',
--rollback     'FIRECRAWL_SEARCH_THEN_SCRAPE', 'STRUCTURED_API', 'EXCHANGE_PUBLIC_API'
--rollback ));
