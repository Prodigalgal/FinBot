--liquibase formatted sql

--changeset codex:019-x-bybit-tradfi-perpetuals splitStatements:true endDelimiter:;
INSERT INTO information_source (
    source_id, display_name, source_mode, source_tier, category, provider,
    trust_weight, poll_interval_seconds, priority, asset_scope, feed_urls,
    seed_urls, search_queries, endpoint_base_url, proxy_route_type,
    maximum_results, maximum_scrape_targets, enabled
) VALUES (
    'source_x_market_search', 'X 市场公开信息', 'FIRECRAWL_SEARCH', 'T3',
    'social_market_intelligence', 'x', 0.5500, 300, 'P1',
    '["BTCUSDT","ETHUSDT","SOLUSDT","XAUUSDT","XAGUSDT","AAPLUSDT","METAUSDT","MSFTUSDT","NVDAUSDT","TSLAUSDT"]',
    '[]', '[]',
    '["site:x.com (markets OR macro OR bitcoin OR crypto OR gold OR silver OR stocks) -giveaway -airdrop"]',
    'https://api.firecrawl.dev/v2', 'FIRECRAWL', 10, 0, TRUE
);

INSERT INTO canonical_product (
    product_id, base_asset, quote_asset, display_name, category, status
) VALUES
    ('product_commodity_xau_usdt', 'XAU', 'USDT', 'Gold / Tether', 'COMMODITY', 'ACTIVE'),
    ('product_commodity_xag_usdt', 'XAG', 'USDT', 'Silver / Tether', 'COMMODITY', 'ACTIVE'),
    ('product_equity_aapl_usdt', 'AAPL', 'USDT', 'Apple / Tether', 'EQUITY', 'ACTIVE'),
    ('product_equity_meta_usdt', 'META', 'USDT', 'Meta Platforms / Tether', 'EQUITY', 'ACTIVE'),
    ('product_equity_msft_usdt', 'MSFT', 'USDT', 'Microsoft / Tether', 'EQUITY', 'ACTIVE'),
    ('product_equity_nvda_usdt', 'NVDA', 'USDT', 'NVIDIA / Tether', 'EQUITY', 'ACTIVE'),
    ('product_equity_tsla_usdt', 'TSLA', 'USDT', 'Tesla / Tether', 'EQUITY', 'ACTIVE');

INSERT INTO venue_instrument (
    instrument_id, product_id, exchange, market_type, symbol, settlement_asset,
    contract_size, price_tick, quantity_step, minimum_quantity, maximum_leverage,
    status, metadata_updated_at
) VALUES
    ('instrument_bybit_xauusdt', 'product_commodity_xau_usdt', 'BYBIT', 'LINEAR_PERPETUAL', 'XAUUSDT', 'USDT', 1, 0.01, 0.001, 0.001, 100, 'ACTIVE', CURRENT_TIMESTAMP),
    ('instrument_bybit_xagusdt', 'product_commodity_xag_usdt', 'BYBIT', 'LINEAR_PERPETUAL', 'XAGUSDT', 'USDT', 1, 0.01, 0.001, 0.001, 100, 'ACTIVE', CURRENT_TIMESTAMP),
    ('instrument_bybit_aaplusdt', 'product_equity_aapl_usdt', 'BYBIT', 'LINEAR_PERPETUAL', 'AAPLUSDT', 'USDT', 1, 0.01, 0.01, 0.01, 50, 'ACTIVE', CURRENT_TIMESTAMP),
    ('instrument_bybit_metausdt', 'product_equity_meta_usdt', 'BYBIT', 'LINEAR_PERPETUAL', 'METAUSDT', 'USDT', 1, 0.01, 0.01, 0.01, 25, 'ACTIVE', CURRENT_TIMESTAMP),
    ('instrument_bybit_msftusdt', 'product_equity_msft_usdt', 'BYBIT', 'LINEAR_PERPETUAL', 'MSFTUSDT', 'USDT', 1, 0.01, 0.01, 0.01, 50, 'ACTIVE', CURRENT_TIMESTAMP),
    ('instrument_bybit_nvdausdt', 'product_equity_nvda_usdt', 'BYBIT', 'LINEAR_PERPETUAL', 'NVDAUSDT', 'USDT', 1, 0.01, 0.01, 0.01, 50, 'ACTIVE', CURRENT_TIMESTAMP),
    ('instrument_bybit_tslausdt', 'product_equity_tsla_usdt', 'BYBIT', 'LINEAR_PERPETUAL', 'TSLAUSDT', 'USDT', 1, 0.01, 0.01, 0.01, 50, 'ACTIVE', CURRENT_TIMESTAMP);

INSERT INTO watchlist_item (
    watchlist_id, product_id, preferred_instrument_id, research_mode, note
) VALUES
    ('watchlist_admin_default', 'product_commodity_xau_usdt', 'instrument_bybit_xauusdt', 'RESEARCH', 'Bybit Demo 公开目录与行情已验证，仅研究'),
    ('watchlist_admin_default', 'product_equity_aapl_usdt', 'instrument_bybit_aaplusdt', 'RESEARCH', 'Bybit Demo 公开目录与行情已验证，仅研究');

--rollback DELETE FROM watchlist_item WHERE watchlist_id = 'watchlist_admin_default' AND product_id IN ('product_commodity_xau_usdt', 'product_equity_aapl_usdt');
--rollback DELETE FROM venue_instrument WHERE instrument_id IN ('instrument_bybit_xauusdt', 'instrument_bybit_xagusdt', 'instrument_bybit_aaplusdt', 'instrument_bybit_metausdt', 'instrument_bybit_msftusdt', 'instrument_bybit_nvdausdt', 'instrument_bybit_tslausdt');
--rollback DELETE FROM canonical_product WHERE product_id IN ('product_commodity_xau_usdt', 'product_commodity_xag_usdt', 'product_equity_aapl_usdt', 'product_equity_meta_usdt', 'product_equity_msft_usdt', 'product_equity_nvda_usdt', 'product_equity_tsla_usdt');
--rollback DELETE FROM information_source WHERE source_id = 'source_x_market_search';
