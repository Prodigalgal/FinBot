--liquibase formatted sql

--changeset codex:035-first-party-source-protocol splitStatements:true endDelimiter:;
ALTER TABLE network_proxy_route
    DROP CONSTRAINT ck_network_proxy_route_type;

ALTER TABLE network_proxy_route
    ADD CONSTRAINT ck_network_proxy_route_type CHECK (route_type IN (
        'WEB_CRAWL', 'FIRECRAWL', 'EXCHANGE_GATE', 'EXCHANGE_BYBIT', 'PUBLIC_DATA'
    ));

ALTER TABLE network_diagnostic_run
    DROP CONSTRAINT ck_network_diagnostic_route;

ALTER TABLE network_diagnostic_run
    ADD CONSTRAINT ck_network_diagnostic_route CHECK (route_type IN (
        'WEB_CRAWL', 'FIRECRAWL', 'EXCHANGE_GATE', 'EXCHANGE_BYBIT', 'PUBLIC_DATA'
    ));

INSERT INTO network_proxy_route (
    route_id, route_type, display_name, require_proxy, allow_direct,
    proxy_url_env, expected_ip_family
) VALUES (
    'route_web_crawl_default', 'WEB_CRAWL', '网页采集 IPv4 代理路由', TRUE, FALSE,
    'FINBOT_PROXY_ROUTE_URLS_JSON', 'IPV4'
);

ALTER TABLE information_source
    DROP CONSTRAINT ck_information_source_mode;

ALTER TABLE information_source
    ADD CONSTRAINT ck_information_source_mode CHECK (source_mode IN (
        'RSS', 'HTML_DOCUMENT', 'FIRECRAWL_SCRAPE', 'FIRECRAWL_SEARCH',
        'FIRECRAWL_SEARCH_THEN_SCRAPE', 'STRUCTURED_API',
        'EXCHANGE_PUBLIC_API'
    ));

UPDATE information_source
SET source_mode = 'HTML_DOCUMENT',
    endpoint_base_url = NULL,
    provider = 'first_party_html',
    proxy_route_type = 'WEB_CRAWL',
    updated_at = CURRENT_TIMESTAMP
WHERE source_mode = 'FIRECRAWL_SCRAPE'
  AND source_id IN (
      'source_eia_weekly', 'source_opec_news', 'source_white_house',
      'source_gate_announcements', 'source_bybit_announcements'
  );

--rollback UPDATE information_source
--rollback SET source_mode = 'FIRECRAWL_SCRAPE',
--rollback     endpoint_base_url = 'https://api.firecrawl.dev/v2',
--rollback     provider = CASE source_id
--rollback         WHEN 'source_eia_weekly' THEN 'eia'
--rollback         WHEN 'source_opec_news' THEN 'opec'
--rollback         WHEN 'source_white_house' THEN 'white_house'
--rollback         WHEN 'source_gate_announcements' THEN 'gate'
--rollback         WHEN 'source_bybit_announcements' THEN 'bybit'
--rollback     END,
--rollback     proxy_route_type = 'FIRECRAWL',
--rollback     updated_at = CURRENT_TIMESTAMP
--rollback WHERE source_mode = 'HTML_DOCUMENT'
--rollback   AND source_id IN (
--rollback       'source_eia_weekly', 'source_opec_news', 'source_white_house',
--rollback       'source_gate_announcements', 'source_bybit_announcements'
--rollback   );
--rollback ALTER TABLE information_source DROP CONSTRAINT ck_information_source_mode;
--rollback ALTER TABLE information_source ADD CONSTRAINT ck_information_source_mode CHECK (source_mode IN (
--rollback     'RSS', 'FIRECRAWL_SCRAPE', 'FIRECRAWL_SEARCH',
--rollback     'FIRECRAWL_SEARCH_THEN_SCRAPE', 'STRUCTURED_API',
--rollback     'EXCHANGE_PUBLIC_API'
--rollback ));
--rollback DELETE FROM network_diagnostic_run WHERE route_type = 'WEB_CRAWL';
--rollback DELETE FROM network_proxy_route WHERE route_type = 'WEB_CRAWL';
--rollback ALTER TABLE network_diagnostic_run DROP CONSTRAINT ck_network_diagnostic_route;
--rollback ALTER TABLE network_diagnostic_run ADD CONSTRAINT ck_network_diagnostic_route CHECK (route_type IN (
--rollback     'FIRECRAWL', 'EXCHANGE_GATE', 'EXCHANGE_BYBIT', 'PUBLIC_DATA'
--rollback ));
--rollback ALTER TABLE network_proxy_route DROP CONSTRAINT ck_network_proxy_route_type;
--rollback ALTER TABLE network_proxy_route ADD CONSTRAINT ck_network_proxy_route_type CHECK (route_type IN (
--rollback     'FIRECRAWL', 'EXCHANGE_GATE', 'EXCHANGE_BYBIT', 'PUBLIC_DATA'
--rollback ));
