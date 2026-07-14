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

UPDATE canonical_product product
SET display_name = desired.display_name,
    category = desired.category,
    status = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
FROM (VALUES
    ('XAUUSDT', 'XAU', 'Gold / Tether', 'COMMODITY'),
    ('XAGUSDT', 'XAG', 'Silver / Tether', 'COMMODITY'),
    ('AAPLUSDT', 'AAPL', 'Apple / Tether', 'EQUITY'),
    ('METAUSDT', 'META', 'Meta Platforms / Tether', 'EQUITY'),
    ('MSFTUSDT', 'MSFT', 'Microsoft / Tether', 'EQUITY'),
    ('NVDAUSDT', 'NVDA', 'NVIDIA / Tether', 'EQUITY'),
    ('TSLAUSDT', 'TSLA', 'Tesla / Tether', 'EQUITY')
) AS desired(symbol, base_asset, display_name, category)
JOIN venue_instrument instrument
  ON instrument.exchange = 'BYBIT'
 AND instrument.market_type = 'LINEAR_PERPETUAL'
 AND instrument.symbol = desired.symbol
WHERE product.product_id = instrument.product_id
  AND product.base_asset = desired.base_asset
  AND product.quote_asset = 'USDT'
  AND product.category <> desired.category
  AND NOT EXISTS (
      SELECT 1
      FROM canonical_product duplicate
      WHERE duplicate.product_id <> product.product_id
        AND duplicate.base_asset = desired.base_asset
        AND duplicate.quote_asset = 'USDT'
        AND duplicate.category = desired.category
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
    ('product_equity_tsla_usdt', 'TSLA', 'USDT', 'Tesla / Tether', 'EQUITY', 'ACTIVE')
ON CONFLICT (base_asset, quote_asset, category) DO UPDATE
SET display_name = EXCLUDED.display_name,
    status = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO venue_instrument (
    instrument_id, product_id, exchange, market_type, symbol, settlement_asset,
    contract_size, price_tick, quantity_step, minimum_quantity, maximum_leverage,
    status, metadata_updated_at
)
SELECT
    COALESCE(existing.instrument_id, desired.instrument_id),
    COALESCE(existing.product_id, product.product_id),
    'BYBIT', 'LINEAR_PERPETUAL', desired.symbol, 'USDT',
    1, desired.price_tick, desired.quantity_step, desired.minimum_quantity,
    desired.maximum_leverage, 'ACTIVE', CURRENT_TIMESTAMP
FROM (VALUES
    ('instrument_bybit_xauusdt', 'XAUUSDT', 'XAU', 'COMMODITY', 0.01, 0.001, 0.001, 100),
    ('instrument_bybit_xagusdt', 'XAGUSDT', 'XAG', 'COMMODITY', 0.01, 0.001, 0.001, 100),
    ('instrument_bybit_aaplusdt', 'AAPLUSDT', 'AAPL', 'EQUITY', 0.01, 0.01, 0.01, 50),
    ('instrument_bybit_metausdt', 'METAUSDT', 'META', 'EQUITY', 0.01, 0.01, 0.01, 25),
    ('instrument_bybit_msftusdt', 'MSFTUSDT', 'MSFT', 'EQUITY', 0.01, 0.01, 0.01, 50),
    ('instrument_bybit_nvdausdt', 'NVDAUSDT', 'NVDA', 'EQUITY', 0.01, 0.01, 0.01, 50),
    ('instrument_bybit_tslausdt', 'TSLAUSDT', 'TSLA', 'EQUITY', 0.01, 0.01, 0.01, 50)
) AS desired(
    instrument_id, symbol, base_asset, category, price_tick,
    quantity_step, minimum_quantity, maximum_leverage
)
JOIN canonical_product product
  ON product.base_asset = desired.base_asset
 AND product.quote_asset = 'USDT'
 AND product.category = desired.category
LEFT JOIN venue_instrument existing
  ON existing.exchange = 'BYBIT'
 AND existing.market_type = 'LINEAR_PERPETUAL'
 AND existing.symbol = desired.symbol
ON CONFLICT (exchange, market_type, symbol) DO UPDATE
SET settlement_asset = EXCLUDED.settlement_asset,
    contract_size = EXCLUDED.contract_size,
    price_tick = EXCLUDED.price_tick,
    quantity_step = EXCLUDED.quantity_step,
    minimum_quantity = EXCLUDED.minimum_quantity,
    maximum_leverage = EXCLUDED.maximum_leverage,
    status = 'ACTIVE',
    metadata_updated_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO watchlist_item (
    watchlist_id, product_id, preferred_instrument_id, research_mode, note
)
SELECT
    'watchlist_admin_default', instrument.product_id, instrument.instrument_id,
    'RESEARCH', desired.note
FROM (VALUES
    ('XAUUSDT', 'Bybit Demo 公开目录与行情已验证，仅研究'),
    ('AAPLUSDT', 'Bybit Demo 公开目录与行情已验证，仅研究')
) AS desired(symbol, note)
JOIN venue_instrument instrument
  ON instrument.exchange = 'BYBIT'
 AND instrument.market_type = 'LINEAR_PERPETUAL'
 AND instrument.symbol = desired.symbol
ON CONFLICT (watchlist_id, product_id) DO UPDATE
SET preferred_instrument_id = EXCLUDED.preferred_instrument_id,
    research_mode = EXCLUDED.research_mode,
    note = EXCLUDED.note,
    updated_at = CURRENT_TIMESTAMP;

--rollback DELETE FROM watchlist_item WHERE watchlist_id = 'watchlist_admin_default' AND product_id IN ('product_commodity_xau_usdt', 'product_equity_aapl_usdt');
--rollback DELETE FROM venue_instrument WHERE instrument_id IN ('instrument_bybit_xauusdt', 'instrument_bybit_xagusdt', 'instrument_bybit_aaplusdt', 'instrument_bybit_metausdt', 'instrument_bybit_msftusdt', 'instrument_bybit_nvdausdt', 'instrument_bybit_tslausdt');
--rollback DELETE FROM canonical_product WHERE product_id IN ('product_commodity_xau_usdt', 'product_commodity_xag_usdt', 'product_equity_aapl_usdt', 'product_equity_meta_usdt', 'product_equity_msft_usdt', 'product_equity_nvda_usdt', 'product_equity_tsla_usdt');
--rollback DELETE FROM information_source WHERE source_id = 'source_x_market_search';
