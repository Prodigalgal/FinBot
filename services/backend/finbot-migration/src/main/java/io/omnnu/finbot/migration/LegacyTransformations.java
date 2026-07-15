package io.omnnu.finbot.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

final class LegacyTransformations {
    private LegacyTransformations() {}

    static Map<String, Long> execute(Connection connection, String importId) throws SQLException {
        var products = executeUpdate(connection, PRODUCTS, importId);
        var instruments = executeUpdate(connection, INSTRUMENTS, importId);
        var aliases = executeUpdate(connection, ALIASES, importId);
        var candles = executeUpdate(connection, CANDLES, importId);
        return Map.of(
                "canonical_products", products,
                "venue_instruments", instruments,
                "instrument_aliases", aliases,
                "market_candles", candles);
    }

    private static long executeUpdate(Connection connection, String sql, String importId) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, importId);
            return statement.executeLargeUpdate();
        }
    }

    private static final String PRODUCTS = """
            WITH source AS (
                SELECT
                    upper(content ->> 'base_asset') AS base_asset,
                    upper(coalesce(nullif(content ->> 'quote_asset', ''), 'USD')) AS quote_asset,
                    content ->> 'display_name' AS display_name,
                    CASE lower(content ->> 'asset_class')
                        WHEN 'commodity' THEN 'COMMODITY'
                        WHEN 'index' THEN 'INDEX'
                        WHEN 'forex' THEN 'FOREX'
                        WHEN 'equity' THEN 'EQUITY'
                        ELSE 'CRYPTO'
                    END AS category,
                    CASE lower(content ->> 'status')
                        WHEN 'active' THEN 'ACTIVE'
                        WHEN 'delisted' THEN 'DELISTED'
                        ELSE 'INACTIVE'
                    END AS status
                FROM legacy_archive_row
                WHERE import_id = ? AND source_table = 'canonical_products'
            ), normalized AS (
                SELECT base_asset, quote_asset, min(display_name) AS display_name,
                       category,
                       CASE WHEN bool_or(status = 'ACTIVE') THEN 'ACTIVE'
                            WHEN bool_or(status = 'DELISTED') THEN 'DELISTED'
                            ELSE 'INACTIVE' END AS status
                FROM source
                WHERE base_asset ~ '^[A-Z0-9]{2,32}$'
                  AND quote_asset ~ '^[A-Z0-9]{2,32}$'
                GROUP BY base_asset, quote_asset, category
            )
            INSERT INTO canonical_product (
                product_id, base_asset, quote_asset, display_name, category, status
            )
            SELECT 'product_legacy_' || substring(md5(base_asset || ':' || quote_asset || ':' || category), 1, 24),
                   base_asset, quote_asset, coalesce(nullif(display_name, ''), base_asset || ' / ' || quote_asset),
                   category, status
            FROM normalized
            ON CONFLICT (base_asset, quote_asset, category) DO UPDATE
            SET display_name = EXCLUDED.display_name,
                status = EXCLUDED.status,
                updated_at = CURRENT_TIMESTAMP
            """;

    private static final String INSTRUMENTS = """
            WITH instruments AS (
                SELECT content
                FROM legacy_archive_row
                WHERE import_id = ? AND source_table = 'venue_instruments'
            ), normalized AS (
                SELECT
                    upper(content ->> 'provider') AS exchange,
                    CASE lower(content ->> 'market_type')
                        WHEN 'spot' THEN 'SPOT'
                        WHEN 'future' THEN 'FUTURE'
                        WHEN 'linear' THEN 'LINEAR_PERPETUAL'
                        ELSE CASE WHEN coalesce((content ->> 'inverse')::boolean, false)
                            THEN 'INVERSE_PERPETUAL' ELSE 'LINEAR_PERPETUAL' END
                    END AS market_type,
                    upper(content ->> 'symbol') AS symbol,
                    upper(content ->> 'base_asset') AS base_asset,
                    upper(coalesce(nullif(content ->> 'quote_asset', ''), 'USD')) AS quote_asset,
                    upper(coalesce(nullif(content ->> 'settle_asset', ''), content ->> 'quote_asset', 'USD')) AS settlement_asset,
                    greatest(coalesce(nullif((content ->> 'contract_size')::numeric, 0), 1), 0.000000000000000001) AS contract_size,
                    greatest(coalesce(nullif((content ->> 'tick_size')::numeric, 0), 0.00000001), 0.000000000000000001) AS price_tick,
                    greatest(coalesce(nullif((content ->> 'amount_step')::numeric, 0), 0.00000001), 0.000000000000000001) AS quantity_step,
                    greatest(coalesce(nullif((content ->> 'min_amount')::numeric, 0), 0.00000001), 0.000000000000000001) AS minimum_quantity,
                    greatest(coalesce(nullif(((content ->> 'leverage_json')::jsonb ->> 'max')::numeric, 0), 1), 1) AS maximum_leverage,
                    CASE WHEN coalesce((content ->> 'active')::integer, 0) = 1 THEN 'ACTIVE' ELSE 'INACTIVE' END AS status,
                    (content ->> 'captured_at')::timestamptz AS metadata_updated_at
                FROM instruments
                WHERE lower(content ->> 'provider') IN ('gate', 'bybit')
            )
            INSERT INTO venue_instrument (
                instrument_id, product_id, exchange, market_type, symbol, settlement_asset,
                contract_size, price_tick, quantity_step, minimum_quantity, maximum_leverage,
                status, metadata_updated_at
            )
            SELECT 'instrument_legacy_' || substring(md5(n.exchange || ':' || n.market_type || ':' || n.symbol), 1, 24),
                   product.product_id, n.exchange, n.market_type, n.symbol, n.settlement_asset,
                   n.contract_size, n.price_tick, n.quantity_step, n.minimum_quantity, n.maximum_leverage,
                   n.status, n.metadata_updated_at
            FROM normalized n
            JOIN canonical_product product
              ON product.base_asset = n.base_asset
             AND product.quote_asset = n.quote_asset
             AND product.category = 'CRYPTO'
            WHERE n.symbol ~ '^[A-Z0-9_-]{2,48}$'
              AND n.settlement_asset ~ '^[A-Z0-9]{2,32}$'
            ON CONFLICT (exchange, market_type, symbol) DO UPDATE
            SET product_id = EXCLUDED.product_id,
                settlement_asset = EXCLUDED.settlement_asset,
                contract_size = EXCLUDED.contract_size,
                price_tick = EXCLUDED.price_tick,
                quantity_step = EXCLUDED.quantity_step,
                minimum_quantity = EXCLUDED.minimum_quantity,
                maximum_leverage = EXCLUDED.maximum_leverage,
                status = EXCLUDED.status,
                metadata_updated_at = EXCLUDED.metadata_updated_at,
                updated_at = CURRENT_TIMESTAMP
            """;

    private static final String ALIASES = """
            WITH aliases AS MATERIALIZED (
                SELECT import_id,
                       upper(content ->> 'alias_key') AS alias,
                       content ->> 'instrument_id' AS instrument_id
                FROM legacy_archive_row
                WHERE import_id = ? AND source_table = 'instrument_aliases'
                  AND content ->> 'instrument_id' IS NOT NULL
            ), source_instruments AS MATERIALIZED (
                SELECT content ->> 'instrument_id' AS instrument_id,
                       upper(content ->> 'provider') AS exchange,
                       CASE lower(content ->> 'market_type')
                           WHEN 'spot' THEN 'SPOT'
                           WHEN 'future' THEN 'FUTURE'
                           WHEN 'linear' THEN 'LINEAR_PERPETUAL'
                           ELSE CASE WHEN coalesce((content ->> 'inverse')::boolean, false)
                               THEN 'INVERSE_PERPETUAL' ELSE 'LINEAR_PERPETUAL' END
                       END AS market_type,
                       upper(content ->> 'symbol') AS symbol
                FROM legacy_archive_row
                WHERE import_id = (SELECT import_id FROM aliases LIMIT 1)
                  AND source_table = 'venue_instruments'
                  AND content ->> 'instrument_id' IS NOT NULL
            ), normalized AS (
                SELECT a.alias, v.exchange, v.market_type, v.symbol
                FROM aliases a
                JOIN source_instruments v
                  ON v.instrument_id = a.instrument_id
            )
            INSERT INTO instrument_alias (instrument_id, alias, alias_type)
            SELECT DISTINCT ON (n.alias) instrument.instrument_id, n.alias, 'LEGACY'
            FROM normalized n
            JOIN venue_instrument instrument
              ON instrument.exchange = n.exchange
             AND instrument.market_type = n.market_type
             AND instrument.symbol = n.symbol
            WHERE n.alias ~ '^[A-Z0-9_-]{1,80}$'
            ORDER BY n.alias, instrument.instrument_id
            ON CONFLICT (alias, alias_type) DO NOTHING
            """;

    private static final String CANDLES = """
            WITH source AS (
                SELECT content
                FROM legacy_archive_row
                WHERE import_id = ? AND source_table = 'market_candles'
            ), normalized AS (
                SELECT
                    upper(content ->> 'provider') AS exchange,
                    CASE lower(content ->> 'market_type')
                        WHEN 'spot' THEN 'SPOT'
                        WHEN 'future' THEN 'FUTURE'
                        WHEN 'linear' THEN 'LINEAR_PERPETUAL'
                        ELSE 'LINEAR_PERPETUAL'
                    END AS market_type,
                    upper(content ->> 'symbol') AS symbol,
                    CASE lower(content ->> 'interval')
                        WHEN '1m' THEN 60 WHEN '3m' THEN 180 WHEN '5m' THEN 300
                        WHEN '15m' THEN 900 WHEN '30m' THEN 1800 WHEN '1h' THEN 3600
                        WHEN '2h' THEN 7200 WHEN '4h' THEN 14400 WHEN '1d' THEN 86400
                        WHEN '1w' THEN 604800 ELSE NULL
                    END AS interval_seconds,
                    (content ->> 'open_time')::timestamptz AS open_time,
                    (content ->> 'open')::numeric AS open_price,
                    (content ->> 'high')::numeric AS high_price,
                    (content ->> 'low')::numeric AS low_price,
                    (content ->> 'close')::numeric AS close_price,
                    coalesce((content ->> 'volume')::numeric, 0) AS volume,
                    (content ->> 'turnover')::numeric AS turnover,
                    (content ->> 'captured_at')::timestamptz AS observed_at
                FROM source
                WHERE lower(content ->> 'provider') IN ('gate', 'bybit')
            )
            INSERT INTO market_candle_fact (
                instrument_id, exchange, environment, symbol, interval_seconds, open_time,
                open_price, high_price, low_price, close_price, volume, turnover,
                source_endpoint, observed_at
            )
            SELECT instrument.instrument_id, n.exchange, 'LIVE', n.symbol, n.interval_seconds, n.open_time,
                   n.open_price, n.high_price, n.low_price, n.close_price, n.volume, n.turnover,
                   'legacy://sqlite/market_candles', n.observed_at
            FROM normalized n
            JOIN venue_instrument instrument
              ON instrument.exchange = n.exchange
             AND instrument.market_type = n.market_type
             AND instrument.symbol = n.symbol
            WHERE n.interval_seconds IS NOT NULL
              AND n.open_price > 0 AND n.high_price > 0 AND n.low_price > 0 AND n.close_price > 0
              AND n.high_price >= greatest(n.open_price, n.close_price, n.low_price)
              AND n.low_price <= least(n.open_price, n.close_price, n.high_price)
              AND n.volume >= 0 AND (n.turnover IS NULL OR n.turnover >= 0)
            ON CONFLICT (instrument_id, environment, interval_seconds, open_time) DO NOTHING
            """;
}
