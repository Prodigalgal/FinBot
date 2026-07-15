package io.omnnu.finbot.infrastructure.catalog;

import static io.omnnu.finbot.infrastructure.jdbc.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.catalog.CatalogInstrumentSnapshot;
import io.omnnu.finbot.application.catalog.CatalogSyncResult;
import io.omnnu.finbot.application.catalog.CatalogSyncRun;
import io.omnnu.finbot.application.catalog.CatalogSyncScope;
import io.omnnu.finbot.application.catalog.ProductCatalogSyncStore;
import io.omnnu.finbot.domain.catalog.ProductCategory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcProductCatalogSyncStore implements ProductCatalogSyncStore {
    private static final int BATCH_SIZE = 500;

    private final JdbcClient jdbcClient;
    private final JdbcTemplate jdbcTemplate;

    public JdbcProductCatalogSyncStore(JdbcClient jdbcClient, JdbcTemplate jdbcTemplate) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public void start(String syncRunId, CatalogSyncScope scope, Instant startedAt) {
        jdbcClient.sql("""
                insert into product_catalog_sync_run (
                  sync_run_id, exchange, market_type, status, started_at
                ) values (
                  :syncRunId, :exchange, :marketType, 'RUNNING', :startedAt
                ) on conflict (sync_run_id) do nothing
                """)
                .param("syncRunId", syncRunId)
                .param("exchange", scope.exchange().name())
                .param("marketType", scope.marketType().name())
                .param("startedAt", timestamp(startedAt))
                .update();
    }

    @Override
    @Transactional
    public CatalogSyncResult complete(
            String syncRunId,
            CatalogSyncScope scope,
            List<CatalogInstrumentSnapshot> instruments,
            Instant completedAt) {
        serializeCatalogPersistence();
        var productMappings = productMappings(instruments);
        insertNewProducts(productMappings.values().stream()
                .filter(ProductMapping::created)
                .distinct()
                .toList(), completedAt);

        jdbcClient.sql("""
                update venue_instrument
                set status = 'INACTIVE', metadata_updated_at = :completedAt,
                    updated_at = :completedAt
                where exchange = :exchange and market_type = :marketType
                """)
                .param("exchange", scope.exchange().name())
                .param("marketType", scope.marketType().name())
                .param("completedAt", timestamp(completedAt))
                .update();

        var persistedInstrumentIds = persistedInstrumentIds(scope);
        var rows = instruments.stream()
                .sorted(Comparator.comparing(CatalogInstrumentSnapshot::symbol))
                .map(snapshot -> new InstrumentRow(
                        persistedInstrumentIds.getOrDefault(
                                snapshot.symbol(),
                                instrumentId(scope, snapshot.symbol())),
                        productMappings.get(new AssetPair(snapshot.baseAsset(), snapshot.quoteAsset())).productId(),
                        snapshot))
                .toList();
        upsertInstruments(scope, rows, completedAt);
        upsertQuotes(rows);
        refreshProductStatuses(completedAt);

        var activeCount = (int) instruments.stream()
                .filter(value -> value.status() == io.omnnu.finbot.domain.catalog.CatalogStatus.ACTIVE)
                .count();
        var inactiveCount = instruments.size() - activeCount;
        jdbcClient.sql("""
                update product_catalog_sync_run
                set status = 'COMPLETED', discovered_count = :discoveredCount,
                    active_count = :activeCount, inactive_count = :inactiveCount,
                    completed_at = :completedAt, error_code = null, error_message = null
                where sync_run_id = :syncRunId
                """)
                .param("syncRunId", syncRunId)
                .param("discoveredCount", instruments.size())
                .param("activeCount", activeCount)
                .param("inactiveCount", inactiveCount)
                .param("completedAt", timestamp(completedAt))
                .update();
        return new CatalogSyncResult(
                syncRunId,
                scope,
                instruments.size(),
                activeCount,
                inactiveCount,
                completedAt);
    }

    @Override
    public void fail(String syncRunId, String errorCode, String safeMessage, Instant failedAt) {
        jdbcClient.sql("""
                update product_catalog_sync_run
                set status = 'FAILED', error_code = :errorCode,
                    error_message = :errorMessage, completed_at = :failedAt
                where sync_run_id = :syncRunId and status = 'RUNNING'
                """)
                .param("syncRunId", syncRunId)
                .param("errorCode", truncate(errorCode, 80))
                .param("errorMessage", truncate(safeMessage, 2_000))
                .param("failedAt", timestamp(failedAt))
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CatalogSyncRun> latestRuns() {
        return jdbcClient.sql("""
                select distinct on (exchange, market_type)
                       sync_run_id, exchange, market_type, status,
                       discovered_count, active_count, inactive_count,
                       error_code, error_message, started_at, completed_at
                from product_catalog_sync_run
                order by exchange, market_type, started_at desc, id desc
                """)
                .query((resultSet, rowNumber) -> new CatalogSyncRun(
                        resultSet.getString("sync_run_id"),
                        new CatalogSyncScope(
                                io.omnnu.finbot.domain.catalog.ExchangeVenue.valueOf(
                                        resultSet.getString("exchange")),
                                io.omnnu.finbot.domain.catalog.MarketType.valueOf(
                                        resultSet.getString("market_type"))),
                        resultSet.getString("status"),
                        resultSet.getInt("discovered_count"),
                        resultSet.getInt("active_count"),
                        resultSet.getInt("inactive_count"),
                        resultSet.getString("error_code"),
                        resultSet.getString("error_message"),
                        resultSet.getObject("started_at", OffsetDateTime.class).toInstant(),
                        nullableInstant(resultSet.getObject("completed_at", OffsetDateTime.class))))
                .list();
    }

    private Map<AssetPair, ProductMapping> productMappings(List<CatalogInstrumentSnapshot> instruments) {
        var existing = new HashMap<AssetPair, ProductMapping>();
        jdbcClient.sql("""
                select product_id, base_asset, quote_asset, category
                from canonical_product
                order by case category when 'CRYPTO' then 2 else 1 end, product_id
                """)
                .query((resultSet, rowNumber) -> new ProductMapping(
                        resultSet.getString("product_id"),
                        new AssetPair(
                                resultSet.getString("base_asset"),
                                resultSet.getString("quote_asset")),
                        ProductCategory.valueOf(resultSet.getString("category")),
                        false))
                .list()
                .forEach(mapping -> existing.putIfAbsent(mapping.assets(), mapping));

        var resolved = new LinkedHashMap<AssetPair, ProductMapping>();
        instruments.stream()
                .map(value -> new AssetPair(value.baseAsset(), value.quoteAsset()))
                .distinct()
                .sorted(Comparator.comparing(AssetPair::base).thenComparing(AssetPair::quote))
                .forEach(assets -> resolved.put(
                        assets,
                        existing.getOrDefault(
                                assets,
                                new ProductMapping(
                                        productId(assets, ProductCategory.CRYPTO),
                                        assets,
                                        ProductCategory.CRYPTO,
                                        true))));
        return resolved;
    }

    private void serializeCatalogPersistence() {
        jdbcTemplate.execute("select pg_advisory_xact_lock(hashtext('finbot'), hashtext('catalog-sync'))");
    }

    private Map<String, String> persistedInstrumentIds(CatalogSyncScope scope) {
        var identifiers = new HashMap<String, String>();
        jdbcClient.sql("""
                select symbol, instrument_id
                from venue_instrument
                where exchange = :exchange and market_type = :marketType
                order by symbol
                """)
                .param("exchange", scope.exchange().name())
                .param("marketType", scope.marketType().name())
                .query((resultSet, rowNumber) -> Map.entry(
                        resultSet.getString("symbol"),
                        resultSet.getString("instrument_id")))
                .list()
                .forEach(entry -> identifiers.put(entry.getKey(), entry.getValue()));
        return identifiers;
    }

    private void insertNewProducts(List<ProductMapping> products, Instant now) {
        if (products.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                insert into canonical_product (
                  product_id, base_asset, quote_asset, display_name,
                  category, status, created_at, updated_at
                ) values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                on conflict (base_asset, quote_asset, category) do update
                set status = 'ACTIVE', updated_at = excluded.updated_at
                """, products, BATCH_SIZE, (statement, product) -> {
                    statement.setString(1, product.productId());
                    statement.setString(2, product.assets().base());
                    statement.setString(3, product.assets().quote());
                    statement.setString(4, product.assets().base() + "/" + product.assets().quote());
                    statement.setString(5, product.category().name());
                    statement.setTimestamp(6, Timestamp.from(now));
                    statement.setTimestamp(7, Timestamp.from(now));
                });
    }

    private void upsertInstruments(CatalogSyncScope scope, List<InstrumentRow> rows, Instant now) {
        jdbcTemplate.batchUpdate("""
                insert into venue_instrument (
                  instrument_id, product_id, exchange, market_type, symbol,
                  settlement_asset, contract_size, price_tick, quantity_step,
                  minimum_quantity, maximum_leverage, execution_enabled, status,
                  metadata_updated_at, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?, ?, ?, ?)
                on conflict (exchange, market_type, symbol) do update
                set product_id = excluded.product_id,
                    settlement_asset = excluded.settlement_asset,
                    contract_size = excluded.contract_size,
                    price_tick = excluded.price_tick,
                    quantity_step = excluded.quantity_step,
                    minimum_quantity = excluded.minimum_quantity,
                    maximum_leverage = excluded.maximum_leverage,
                    status = excluded.status,
                    metadata_updated_at = excluded.metadata_updated_at,
                    updated_at = excluded.updated_at
                """, rows, BATCH_SIZE, (statement, row) -> {
                    var value = row.snapshot();
                    statement.setString(1, row.instrumentId());
                    statement.setString(2, row.productId());
                    statement.setString(3, scope.exchange().name());
                    statement.setString(4, scope.marketType().name());
                    statement.setString(5, value.symbol());
                    statement.setString(6, value.settlementAsset());
                    statement.setBigDecimal(7, value.contractSize());
                    statement.setBigDecimal(8, value.priceTick());
                    statement.setBigDecimal(9, value.quantityStep());
                    statement.setBigDecimal(10, value.minimumQuantity());
                    statement.setBigDecimal(11, value.maximumLeverage());
                    statement.setString(12, value.status().name());
                    statement.setTimestamp(13, Timestamp.from(value.observedAt()));
                    statement.setTimestamp(14, Timestamp.from(now));
                    statement.setTimestamp(15, Timestamp.from(now));
                });
    }

    private void upsertQuotes(List<InstrumentRow> rows) {
        var quoted = rows.stream().filter(value -> value.snapshot().latestPrice() != null).toList();
        if (quoted.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                insert into instrument_quote_snapshot (
                  instrument_id, last_price, observed_at, updated_at
                ) values (?, ?, ?, ?)
                on conflict (instrument_id) do update
                set last_price = excluded.last_price,
                    observed_at = excluded.observed_at,
                    updated_at = excluded.updated_at
                where instrument_quote_snapshot.observed_at <= excluded.observed_at
                """, quoted, BATCH_SIZE, (statement, row) -> {
                    statement.setString(1, row.instrumentId());
                    statement.setBigDecimal(2, row.snapshot().latestPrice());
                    statement.setTimestamp(3, Timestamp.from(row.snapshot().observedAt()));
                    statement.setTimestamp(4, Timestamp.from(row.snapshot().observedAt()));
                });
    }

    private void refreshProductStatuses(Instant now) {
        jdbcClient.sql("""
                update canonical_product product
                set status = case when exists (
                      select 1 from venue_instrument instrument
                      where instrument.product_id = product.product_id
                        and instrument.status = 'ACTIVE'
                    ) then 'ACTIVE' else 'INACTIVE' end,
                    updated_at = :now
                where status <> case when exists (
                      select 1 from venue_instrument instrument
                      where instrument.product_id = product.product_id
                        and instrument.status = 'ACTIVE'
                    ) then 'ACTIVE' else 'INACTIVE' end
                """)
                .param("now", timestamp(now))
                .update();
    }

    private static String productId(AssetPair assets, ProductCategory category) {
        var slug = slug(assets.base() + '_' + assets.quote(), 48);
        return "product_" + slug + '_' + hash(assets.base() + '|' + assets.quote() + '|' + category).substring(0, 12);
    }

    private static String instrumentId(CatalogSyncScope scope, String symbol) {
        var slug = slug(scope.exchange().name() + '_' + scope.marketType().name() + '_' + symbol, 44);
        return "instrument_" + slug + '_' + hash(
                scope.exchange() + "|" + scope.marketType() + "|" + symbol).substring(0, 12);
    }

    private static String slug(String value, int maximumLength) {
        var normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.substring(0, Math.min(normalized.length(), maximumLength));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String truncate(String value, int maximumLength) {
        if (value == null) {
            return null;
        }
        var normalized = value.strip();
        return normalized.substring(0, Math.min(normalized.length(), maximumLength));
    }

    private static Instant nullableInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record AssetPair(String base, String quote) {
    }

    private record ProductMapping(
            String productId,
            AssetPair assets,
            ProductCategory category,
            boolean created) {
    }

    private record InstrumentRow(
            String instrumentId,
            String productId,
            CatalogInstrumentSnapshot snapshot) {
    }
}
