--liquibase formatted sql

--changeset codex:044-free-structured-source-catalog-v2 splitStatements:true endDelimiter:;
ALTER TABLE information_source_catalog_manifest
    DROP CONSTRAINT information_source_catalog_manifest_pkey;

ALTER TABLE information_source_catalog_manifest
    DROP CONSTRAINT IF EXISTS information_source_catalog_manifest_catalog_version_key;

ALTER TABLE information_source_catalog_manifest
    ADD PRIMARY KEY (catalog_id, catalog_version);

CREATE INDEX ix_information_source_catalog_latest
    ON information_source_catalog_manifest (catalog_id, created_at DESC, catalog_version DESC);

UPDATE information_source
SET source_mode = 'JSON_API',
    provider = 'eia',
    feed_urls = '[]'::jsonb,
    seed_urls = '[]'::jsonb,
    search_queries = '[]'::jsonb,
    endpoint_base_url = 'https://api.eia.gov/v2/petroleum/sum/sndw/data/?frequency=weekly&data%5B0%5D=value&sort%5B0%5D%5Bcolumn%5D=period&sort%5B0%5D%5Bdirection%5D=desc&offset=0&length=20',
    credential_env = 'FINBOT_INFORMATION_SOURCE_KEYS_JSON',
    proxy_route_type = 'PUBLIC_DATA',
    maximum_results = 1,
    maximum_scrape_targets = 0,
    enabled = FALSE,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE source_id = 'source_eia_weekly'
  AND source_mode = 'HTML_DOCUMENT' AND provider = 'first_party_html'
  AND endpoint_base_url IS NULL AND deleted_at IS NULL;

UPDATE information_source
SET source_mode = 'JSON_API',
    provider = 'bybit',
    feed_urls = '[]'::jsonb,
    seed_urls = '[]'::jsonb,
    search_queries = '[]'::jsonb,
    endpoint_base_url = 'https://api.bybit.com/v5/announcements/index?locale=en-US&limit=20',
    credential_env = NULL,
    proxy_route_type = 'PUBLIC_DATA',
    maximum_results = 1,
    maximum_scrape_targets = 0,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE source_id = 'source_bybit_announcements'
  AND source_mode = 'HTML_DOCUMENT' AND provider = 'first_party_html'
  AND endpoint_base_url IS NULL AND deleted_at IS NULL;

UPDATE information_source
SET display_name = '白宫简报 RSS',
    source_mode = 'RSS',
    provider = 'white_house',
    feed_urls = '["https://www.whitehouse.gov/briefings-statements/feed/"]'::jsonb,
    seed_urls = '[]'::jsonb,
    search_queries = '[]'::jsonb,
    endpoint_base_url = NULL,
    credential_env = NULL,
    proxy_route_type = 'PUBLIC_DATA',
    maximum_results = 20,
    maximum_scrape_targets = 0,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE source_id = 'source_white_house'
  AND source_mode = 'HTML_DOCUMENT' AND provider = 'first_party_html'
  AND endpoint_base_url IS NULL AND deleted_at IS NULL;

UPDATE information_source
SET seed_urls = '["https://www.opec.org/press-releases.html"]'::jsonb,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE source_id = 'source_opec_news'
  AND source_mode = 'HTML_DOCUMENT' AND provider = 'first_party_html'
  AND endpoint_base_url IS NULL AND deleted_at IS NULL;

UPDATE information_source
SET display_name = 'GDELT 全球新闻发现',
    provider = 'gdelt',
    search_queries = '["markets OR inflation OR central bank OR oil OR cryptocurrency"]'::jsonb,
    endpoint_base_url = 'https://api.gdeltproject.org/api/v2/doc/doc',
    credential_env = NULL,
    proxy_route_type = 'WEB_CRAWL',
    maximum_results = 20,
    maximum_scrape_targets = 0,
    enabled = TRUE,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE source_id = 'source_global_search'
  AND source_mode = 'SEARCH_DISCOVERY' AND provider = 'searxng'
  AND endpoint_base_url IS NULL AND deleted_at IS NULL;

INSERT INTO information_source (
    source_id, display_name, source_mode, source_tier, category, provider,
    trust_weight, poll_interval_seconds, priority, asset_scope, feed_urls,
    seed_urls, search_queries, endpoint_base_url, credential_env,
    proxy_route_type, maximum_results, maximum_scrape_targets, enabled
) VALUES
    ('source_sec_edgar', 'SEC EDGAR 最新申报', 'RSS', 'T1', 'regulatory_filings', 'sec_edgar',
     0.98, 900, 'P0', '["AAPLUSDT","TSLAUSDT","NAS100","SPX500"]',
     '["https://www.sec.gov/cgi-bin/browse-edgar?action=getcurrent&count=100&output=atom"]',
     '[]', '[]', NULL, NULL, 'WEB_CRAWL', 25, 0, TRUE),
    ('source_world_bank_macro', 'World Bank 全球宏观指标', 'JSON_API', 'T1', 'macro', 'world_bank',
     0.95, 86400, 'P1', '["NAS100","XAUUSD","DXY","BTCUSDT"]',
     '[]', '[]', '[]',
     'https://api.worldbank.org/v2/country/WLD/indicator/NY.GDP.MKTP.CD?format=json&per_page=20',
     NULL, 'PUBLIC_DATA', 1, 0, TRUE),
    ('source_bls_labor', 'BLS 美国失业率', 'JSON_API', 'T1', 'macro_labor', 'bls',
     0.97, 21600, 'P0', '["NAS100","SPX500","XAUUSD","DXY"]',
     '[]', '[]', '[]',
     'https://api.bls.gov/publicAPI/v1/timeseries/data/LNS14000000',
     NULL, 'PUBLIC_DATA', 1, 0, TRUE),
    ('source_cftc_cot', 'CFTC 交易商持仓报告', 'JSON_API', 'T1', 'positioning', 'cftc',
     0.97, 21600, 'P0', '["XAUUSD","XTIUSD","USOIL","NAS100"]',
     '[]', '[]', '[]',
     'https://publicreporting.cftc.gov/resource/gpe5-46if.json?%24limit=100&%24order=report_date_as_yyyy_mm_dd%20DESC',
     NULL, 'PUBLIC_DATA', 1, 0, TRUE),
    ('source_fred_macro', 'FRED 联邦基金利率', 'JSON_API', 'T1', 'macro_rates', 'fred',
     0.98, 3600, 'P0', '["NAS100","SPX500","XAUUSD","DXY","BTCUSDT"]',
     '[]', '[]', '[]',
     'https://api.stlouisfed.org/fred/series/observations?series_id=DFF&file_type=json&sort_order=desc&limit=100',
     'FINBOT_INFORMATION_SOURCE_KEYS_JSON', 'PUBLIC_DATA', 1, 0, FALSE)
ON CONFLICT (source_id) DO NOTHING;

INSERT INTO information_source_catalog_manifest (
    catalog_id, catalog_version, manifest_hash, source_count, source_ids
) VALUES (
    'catalog_default_sources',
    'v2',
    '94617c0d468a6f1d4f1ebcaa250e5c3ea7d2ad3eb92358203275c3679f1f5463',
    16,
    '["source_ap_search","source_bls_labor","source_bybit_announcements",
      "source_cftc_cot","source_ecb_official","source_eia_weekly",
      "source_federal_reserve","source_fred_macro","source_gate_announcements",
      "source_global_search","source_opec_news","source_reuters_search",
      "source_sec_edgar","source_white_house","source_world_bank_macro",
      "source_x_market_search"]'::jsonb
);

--rollback UPDATE information_source SET source_mode = 'HTML_DOCUMENT', provider = 'first_party_html', feed_urls = '[]'::jsonb, seed_urls = '["https://www.eia.gov/petroleum/supply/weekly/"]'::jsonb, search_queries = '[]'::jsonb, endpoint_base_url = NULL, credential_env = NULL, proxy_route_type = 'WEB_CRAWL', maximum_results = 5, maximum_scrape_targets = 1, enabled = TRUE, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE source_id = 'source_eia_weekly' AND provider = 'eia';
--rollback UPDATE information_source SET source_mode = 'HTML_DOCUMENT', provider = 'first_party_html', feed_urls = '[]'::jsonb, seed_urls = '["https://www.opec.org/opec_web/en/press_room/28.htm"]'::jsonb, search_queries = '[]'::jsonb, endpoint_base_url = NULL, credential_env = NULL, proxy_route_type = 'WEB_CRAWL', maximum_results = 5, maximum_scrape_targets = 2, enabled = TRUE, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE source_id = 'source_opec_news' AND seed_urls = '["https://www.opec.org/press-releases.html"]'::jsonb;
--rollback UPDATE information_source SET display_name = '白宫简报', source_mode = 'HTML_DOCUMENT', provider = 'first_party_html', feed_urls = '[]'::jsonb, seed_urls = '["https://www.whitehouse.gov/briefing-room/"]'::jsonb, search_queries = '[]'::jsonb, endpoint_base_url = NULL, credential_env = NULL, proxy_route_type = 'WEB_CRAWL', maximum_results = 10, maximum_scrape_targets = 3, enabled = TRUE, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE source_id = 'source_white_house' AND provider = 'white_house';
--rollback UPDATE information_source SET source_mode = 'HTML_DOCUMENT', provider = 'first_party_html', feed_urls = '[]'::jsonb, seed_urls = '["https://announcements.bybit.com/"]'::jsonb, search_queries = '[]'::jsonb, endpoint_base_url = NULL, credential_env = NULL, proxy_route_type = 'WEB_CRAWL', maximum_results = 10, maximum_scrape_targets = 3, enabled = TRUE, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE source_id = 'source_bybit_announcements' AND provider = 'bybit';
--rollback UPDATE information_source SET display_name = '通用互联网搜索发现', source_mode = 'SEARCH_DISCOVERY', provider = 'searxng', feed_urls = '[]'::jsonb, seed_urls = '[]'::jsonb, search_queries = '["crypto markets macro commodities latest"]'::jsonb, endpoint_base_url = NULL, credential_env = NULL, proxy_route_type = 'WEB_CRAWL', maximum_results = 10, maximum_scrape_targets = 0, enabled = FALSE, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE source_id = 'source_global_search' AND provider = 'gdelt';
--rollback DELETE FROM information_source_catalog_manifest WHERE catalog_id = 'catalog_default_sources' AND catalog_version = 'v2';
--rollback ALTER TABLE information_source_catalog_manifest DROP CONSTRAINT information_source_catalog_manifest_pkey;
--rollback DROP INDEX IF EXISTS ix_information_source_catalog_latest;
--rollback ALTER TABLE information_source_catalog_manifest ADD PRIMARY KEY (catalog_id);
--rollback ALTER TABLE information_source_catalog_manifest ADD CONSTRAINT information_source_catalog_manifest_catalog_version_key UNIQUE (catalog_version);
--rollback UPDATE information_source SET enabled = FALSE, deleted_at = CURRENT_TIMESTAMP, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE source_id IN ('source_sec_edgar','source_world_bank_macro','source_bls_labor','source_cftc_cot','source_fred_macro') AND deleted_at IS NULL;
