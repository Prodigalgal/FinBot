--liquibase formatted sql

--changeset codex:049-public-searxng-instance-pool splitStatements:true endDelimiter:;
INSERT INTO information_source (
    source_id, display_name, source_mode, source_tier, category, provider,
    trust_weight, poll_interval_seconds, priority, asset_scope, feed_urls,
    seed_urls, search_queries, endpoint_base_url, credential_env,
    proxy_route_type, maximum_results, maximum_scrape_targets, enabled
) VALUES (
    'source_searxng_public_pool',
    '公共 SearXNG 实例池',
    'SEARCH_DISCOVERY',
    'T4',
    'broad_news_discovery',
    'searxng_public_pool',
    0.42,
    21600,
    'P3',
    '[]',
    '[]',
    '[]',
    '["全球与中国综合新闻、金融、科技、农业、医疗、能源和监管最新重要事件及原始出处"]',
    'https://searx.space/data/instances.json',
    NULL,
    'WEB_CRAWL',
    20,
    0,
    TRUE
) ON CONFLICT (source_id) DO NOTHING;

INSERT INTO information_source_catalog_manifest (
    catalog_id, catalog_version, manifest_hash, source_count, source_ids
) VALUES (
    'catalog_default_sources',
    'v4',
    '24a5f8c50a624f60789b1c8dbe17bac0048017695b931750e6a4e5251276ed46',
    62,
    '["source_36kr_cn","source_ai_gemini_web_search","source_ai_grok_web_search",
      "source_aljazeera_news","source_ap_search","source_arxiv_ai","source_bbc_news","source_binance_announcements",
      "source_bitget_announcements","source_bls_labor","source_bybit_announcements",
      "source_cbs_news","source_cftc_cot","source_chinanews_cn","source_cisa_advisories","source_cnbc_news",
      "source_cnbeta_cn","source_coinbase_news","source_coindesk_news","source_dw_news","source_ecb_official",
      "source_eia_weekly","source_fda_safety","source_federal_register","source_federal_reserve",
      "source_france24_news","source_fred_macro","source_gate_announcements",
      "source_github_security","source_global_search","source_guardian_news",
      "source_ifeng_cn","source_ithome_cn","source_kraken_news","source_kucoin_announcements","source_marketwatch_news",
      "source_nasa_news","source_noaa_nhc","source_npr_news","source_nvd_cves",
      "source_nyt_news","source_okx_announcements","source_opec_news","source_oschina_cn",
      "source_people_cn","source_reuters_search",
      "source_scmp_news","source_searxng_cn_finance","source_searxng_cn_mainstream",
      "source_searxng_global_search","source_searxng_news_search","source_searxng_public_pool","source_sec_edgar",
      "source_sina_cn","source_sspai_cn","source_usda_ars","source_usda_wasde",
      "source_usgs_earthquakes","source_white_house","source_who_news",
      "source_world_bank_macro","source_x_market_search"]'::jsonb
);

--rollback DELETE FROM information_source_catalog_manifest WHERE catalog_id = 'catalog_default_sources' AND catalog_version = 'v4';
--rollback UPDATE information_source SET enabled = FALSE, deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP), version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE source_id = 'source_searxng_public_pool' AND source_mode = 'SEARCH_DISCOVERY' AND provider = 'searxng_public_pool' AND endpoint_base_url = 'https://searx.space/data/instances.json' AND proxy_route_type = 'WEB_CRAWL' AND version = 0 AND deleted_at IS NULL;
