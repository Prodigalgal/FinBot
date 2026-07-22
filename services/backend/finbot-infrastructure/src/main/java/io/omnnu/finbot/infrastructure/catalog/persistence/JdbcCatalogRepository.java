package io.omnnu.finbot.infrastructure.catalog.persistence;

import static io.omnnu.finbot.infrastructure.jdbc.persistence.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.catalog.port.out.CatalogRepository;
import io.omnnu.finbot.application.catalog.dto.ProductDetail;
import io.omnnu.finbot.application.catalog.dto.ProductPage;
import io.omnnu.finbot.application.catalog.dto.ProductSearchCriteria;
import io.omnnu.finbot.application.catalog.dto.ProductSummary;
import io.omnnu.finbot.application.catalog.dto.VenueInstrumentView;
import io.omnnu.finbot.application.catalog.dto.WatchlistDetail;
import io.omnnu.finbot.application.catalog.dto.WatchlistItemView;
import io.omnnu.finbot.application.catalog.dto.WatchlistMembership;
import io.omnnu.finbot.application.catalog.dto.WatchlistSummary;
import io.omnnu.finbot.domain.catalog.CatalogStatus;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.catalog.MarketType;
import io.omnnu.finbot.domain.catalog.ProductCategory;
import io.omnnu.finbot.domain.catalog.ProductId;
import io.omnnu.finbot.domain.catalog.WatchlistId;
import io.omnnu.finbot.domain.catalog.WatchlistResearchMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcCatalogRepository implements CatalogRepository {
    private final JdbcClient jdbcClient;

    public JdbcCatalogRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional(readOnly = true)
    public ProductPage searchProducts(ProductSearchCriteria criteria) {
        var sql = new StringBuilder("""
                select p.product_id, p.base_asset, p.quote_asset, p.display_name,
                       p.category, p.status,
                       (select count(*) from venue_instrument vi
                         where vi.product_id = p.product_id and vi.status = 'ACTIVE') as instrument_count,
                       (select wi.research_mode
                        from watchlist_item wi
                        join watchlist w on w.watchlist_id = wi.watchlist_id
                        where wi.product_id = p.product_id and w.owner_id = :ownerId
                        order by case wi.research_mode
                            when 'PINNED' then 3 when 'RESEARCH' then 2 else 1 end desc,
                            wi.updated_at desc
                        limit 1) as highest_watchlist_mode
                from canonical_product p
                where p.status = 'ACTIVE'
                """);
        if (criteria.search() != null) {
            sql.append(" and (lower(p.display_name) like :search or lower(p.base_asset) like :search ")
                    .append("or lower(p.quote_asset) like :search or exists (")
                    .append("select 1 from venue_instrument vsi where vsi.product_id = p.product_id ")
                    .append("and lower(vsi.symbol) like :search))");
        }
        if (criteria.category() != null) {
            sql.append(" and p.category = :category");
        }
        if (criteria.exchange() != null || criteria.marketType() != null) {
            sql.append(" and exists (select 1 from venue_instrument vf where vf.product_id = p.product_id");
            if (criteria.exchange() != null) {
                sql.append(" and vf.exchange = :exchange");
            }
            if (criteria.marketType() != null) {
                sql.append(" and vf.market_type = :marketType");
            }
            sql.append(" and vf.status = 'ACTIVE')");
        }
        if (criteria.after() != null) {
            sql.append(" and p.product_id > :after");
        }
        sql.append(" order by p.product_id limit :limit");

        var statement = jdbcClient.sql(sql.toString())
                .param("ownerId", criteria.ownerId())
                .param("limit", criteria.limit() + 1);
        if (criteria.search() != null) {
            statement = statement.param("search", "%" + criteria.search().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        if (criteria.category() != null) {
            statement = statement.param("category", criteria.category().name());
        }
        if (criteria.exchange() != null) {
            statement = statement.param("exchange", criteria.exchange().name());
        }
        if (criteria.marketType() != null) {
            statement = statement.param("marketType", criteria.marketType().name());
        }
        if (criteria.after() != null) {
            statement = statement.param("after", criteria.after().value());
        }
        var rows = new ArrayList<>(statement
                .query((resultSet, rowNumber) -> productSummary(resultSet))
                .list());
        var hasMore = rows.size() > criteria.limit();
        if (hasMore) {
            rows.removeLast();
        }
        var nextCursor = hasMore && !rows.isEmpty() ? rows.getLast().productId() : null;
        return new ProductPage(rows, nextCursor, countProducts(criteria));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductDetail> findProduct(ProductId productId, String ownerId) {
        var root = jdbcClient.sql("""
                select product_id, base_asset, quote_asset, display_name, category, status
                from canonical_product where product_id = :productId
                """)
                .param("productId", productId.value())
                .query((resultSet, rowNumber) -> new ProductRoot(
                        new ProductId(resultSet.getString("product_id")),
                        resultSet.getString("base_asset"),
                        resultSet.getString("quote_asset"),
                        resultSet.getString("display_name"),
                        ProductCategory.valueOf(resultSet.getString("category")),
                        CatalogStatus.valueOf(resultSet.getString("status"))))
                .optional();
        if (root.isEmpty()) {
            return Optional.empty();
        }
        var instruments = jdbcClient.sql("""
                select instrument.instrument_id, instrument.exchange, instrument.market_type,
                       instrument.symbol, instrument.settlement_asset, instrument.contract_size,
                       instrument.price_tick, instrument.quantity_step, instrument.minimum_quantity,
                       instrument.maximum_leverage, instrument.execution_enabled, instrument.status,
                       instrument.metadata_updated_at,
                       coalesce(latest.close_price, quote.last_price) as latest_price,
                       coalesce(latest.observed_at, quote.observed_at) as latest_price_at
                from venue_instrument instrument
                left join lateral (
                  select candle.close_price, candle.observed_at
                  from market_candle_fact candle
                  where candle.instrument_id = instrument.instrument_id
                    and candle.environment = 'LIVE'
                  order by candle.open_time desc, candle.id desc
                  limit 1
                ) latest on true
                left join instrument_quote_snapshot quote
                  on quote.instrument_id = instrument.instrument_id
                where instrument.product_id = :productId
                order by instrument.status, instrument.exchange,
                         instrument.market_type, instrument.symbol
                """)
                .param("productId", productId.value())
                .query((resultSet, rowNumber) -> instrument(resultSet))
                .list();
        var memberships = jdbcClient.sql("""
                select w.watchlist_id, w.name, wi.research_mode,
                       wi.preferred_instrument_id, wi.note
                from watchlist_item wi
                join watchlist w on w.watchlist_id = wi.watchlist_id
                where wi.product_id = :productId and w.owner_id = :ownerId
                order by w.is_default desc, w.name
                """)
                .param("productId", productId.value())
                .param("ownerId", ownerId)
                .query((resultSet, rowNumber) -> new WatchlistMembership(
                        new WatchlistId(resultSet.getString("watchlist_id")),
                        resultSet.getString("name"),
                        WatchlistResearchMode.valueOf(resultSet.getString("research_mode")),
                        nullableInstrumentId(resultSet.getString("preferred_instrument_id")),
                        resultSet.getString("note")))
                .list();
        var value = root.orElseThrow();
        return Optional.of(new ProductDetail(
                value.productId(),
                value.baseAsset(),
                value.quoteAsset(),
                value.displayName(),
                value.category(),
                value.status(),
                instruments,
                memberships));
    }

    @Override
    @Transactional(readOnly = true)
    public List<WatchlistSummary> listWatchlists(String ownerId) {
        return jdbcClient.sql("""
                select w.watchlist_id, w.name, w.description, w.is_default, w.version, w.updated_at,
                       count(wi.id) as item_count
                from watchlist w
                left join watchlist_item wi on wi.watchlist_id = w.watchlist_id
                where w.owner_id = :ownerId
                group by w.id
                order by w.is_default desc, w.name, w.watchlist_id
                """)
                .param("ownerId", ownerId)
                .query((resultSet, rowNumber) -> new WatchlistSummary(
                        new WatchlistId(resultSet.getString("watchlist_id")),
                        resultSet.getString("name"),
                        resultSet.getString("description"),
                        resultSet.getBoolean("is_default"),
                        resultSet.getInt("item_count"),
                        resultSet.getLong("version"),
                        instant(resultSet.getObject("updated_at", OffsetDateTime.class))))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WatchlistDetail> findWatchlist(WatchlistId watchlistId, String ownerId) {
        var root = jdbcClient.sql("""
                select name, description, is_default, version, updated_at
                from watchlist
                where watchlist_id = :watchlistId and owner_id = :ownerId
                """)
                .param("watchlistId", watchlistId.value())
                .param("ownerId", ownerId)
                .query((resultSet, rowNumber) -> new WatchlistRoot(
                        resultSet.getString("name"),
                        resultSet.getString("description"),
                        resultSet.getBoolean("is_default"),
                        resultSet.getLong("version"),
                        instant(resultSet.getObject("updated_at", OffsetDateTime.class))))
                .optional();
        if (root.isEmpty()) {
            return Optional.empty();
        }
        var items = jdbcClient.sql("""
                select wi.product_id, p.display_name, p.base_asset, p.quote_asset,
                       wi.research_mode, wi.preferred_instrument_id, wi.note, wi.updated_at
                from watchlist_item wi
                join canonical_product p on p.product_id = wi.product_id
                where wi.watchlist_id = :watchlistId
                order by case wi.research_mode
                    when 'PINNED' then 3 when 'RESEARCH' then 2 else 1 end desc,
                    wi.updated_at desc, wi.product_id
                """)
                .param("watchlistId", watchlistId.value())
                .query((resultSet, rowNumber) -> new WatchlistItemView(
                        new ProductId(resultSet.getString("product_id")),
                        resultSet.getString("display_name"),
                        resultSet.getString("base_asset"),
                        resultSet.getString("quote_asset"),
                        WatchlistResearchMode.valueOf(resultSet.getString("research_mode")),
                        nullableInstrumentId(resultSet.getString("preferred_instrument_id")),
                        resultSet.getString("note"),
                        instant(resultSet.getObject("updated_at", OffsetDateTime.class))))
                .list();
        var value = root.orElseThrow();
        return Optional.of(new WatchlistDetail(
                watchlistId,
                value.name(),
                value.description(),
                value.defaultWatchlist(),
                value.version(),
                value.updatedAt(),
                items));
    }

    @Override
    public void createWatchlist(
            WatchlistId watchlistId,
            String ownerId,
            String name,
            String description,
            Instant createdAt) {
        jdbcClient.sql("""
                insert into watchlist (
                  watchlist_id, owner_id, name, description, is_default,
                  version, created_at, updated_at
                ) values (
                  :watchlistId, :ownerId, :name, :description, false,
                  0, :createdAt, :createdAt
                )
                """)
                .param("watchlistId", watchlistId.value())
                .param("ownerId", ownerId)
                .param("name", name)
                .param("description", description)
                .param("createdAt", timestamp(createdAt))
                .update();
    }

    @Override
    public boolean updateWatchlist(
            WatchlistId watchlistId,
            String ownerId,
            String name,
            String description,
            long expectedVersion,
            Instant updatedAt) {
        return jdbcClient.sql("""
                update watchlist
                set name = :name,
                    description = :description,
                    version = version + 1,
                    updated_at = :updatedAt
                where watchlist_id = :watchlistId
                  and owner_id = :ownerId
                  and version = :expectedVersion
                """)
                .param("watchlistId", watchlistId.value())
                .param("ownerId", ownerId)
                .param("name", name)
                .param("description", description)
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", timestamp(updatedAt))
                .update() == 1;
    }

    @Override
    public boolean deleteWatchlist(WatchlistId watchlistId, String ownerId) {
        return jdbcClient.sql("""
                delete from watchlist
                where watchlist_id = :watchlistId and owner_id = :ownerId and is_default = false
                """)
                .param("watchlistId", watchlistId.value())
                .param("ownerId", ownerId)
                .update() == 1;
    }

    @Override
    public boolean upsertWatchlistItem(
            WatchlistId watchlistId,
            String ownerId,
            ProductId productId,
            InstrumentId preferredInstrumentId,
            WatchlistResearchMode researchMode,
            String note,
            Instant updatedAt) {
        return jdbcClient.sql("""
                insert into watchlist_item (
                  watchlist_id, product_id, preferred_instrument_id, research_mode,
                  note, created_at, updated_at
                )
                select :watchlistId, :productId, :preferredInstrumentId, :researchMode,
                       :note, :updatedAt, :updatedAt
                where exists (
                  select 1 from watchlist
                  where watchlist_id = :watchlistId and owner_id = :ownerId
                ) and exists (
                  select 1 from canonical_product where product_id = :productId
                )
                on conflict (watchlist_id, product_id) do update
                set preferred_instrument_id = excluded.preferred_instrument_id,
                    research_mode = excluded.research_mode,
                    note = excluded.note,
                    updated_at = excluded.updated_at
                """)
                .param("watchlistId", watchlistId.value())
                .param("ownerId", ownerId)
                .param("productId", productId.value())
                .param("preferredInstrumentId", preferredInstrumentId == null ? null : preferredInstrumentId.value())
                .param("researchMode", researchMode.name())
                .param("note", note)
                .param("updatedAt", timestamp(updatedAt))
                .update() == 1;
    }

    @Override
    public boolean removeWatchlistItem(
            WatchlistId watchlistId,
            String ownerId,
            ProductId productId) {
        return jdbcClient.sql("""
                delete from watchlist_item wi
                using watchlist w
                where wi.watchlist_id = w.watchlist_id
                  and wi.watchlist_id = :watchlistId
                  and wi.product_id = :productId
                  and w.owner_id = :ownerId
                """)
                .param("watchlistId", watchlistId.value())
                .param("productId", productId.value())
                .param("ownerId", ownerId)
                .update() == 1;
    }

    private static ProductSummary productSummary(ResultSet resultSet) throws SQLException {
        var mode = resultSet.getString("highest_watchlist_mode");
        return new ProductSummary(
                new ProductId(resultSet.getString("product_id")),
                resultSet.getString("base_asset"),
                resultSet.getString("quote_asset"),
                resultSet.getString("display_name"),
                ProductCategory.valueOf(resultSet.getString("category")),
                CatalogStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("instrument_count"),
                mode == null ? null : WatchlistResearchMode.valueOf(mode));
    }

    private static VenueInstrumentView instrument(ResultSet resultSet) throws SQLException {
        return new VenueInstrumentView(
                new InstrumentId(resultSet.getString("instrument_id")),
                ExchangeVenue.valueOf(resultSet.getString("exchange")),
                MarketType.valueOf(resultSet.getString("market_type")),
                resultSet.getString("symbol"),
                resultSet.getString("settlement_asset"),
                resultSet.getBigDecimal("contract_size"),
                resultSet.getBigDecimal("price_tick"),
                resultSet.getBigDecimal("quantity_step"),
                resultSet.getBigDecimal("minimum_quantity"),
                resultSet.getBigDecimal("maximum_leverage"),
                resultSet.getBoolean("execution_enabled"),
                CatalogStatus.valueOf(resultSet.getString("status")),
                instant(resultSet.getObject("metadata_updated_at", OffsetDateTime.class)),
                resultSet.getBigDecimal("latest_price"),
                nullableInstant(resultSet.getObject("latest_price_at", OffsetDateTime.class)));
    }

    private long countProducts(ProductSearchCriteria criteria) {
        var sql = new StringBuilder("""
                select count(*) from canonical_product p where p.status = 'ACTIVE'
                """);
        if (criteria.search() != null) {
            sql.append(" and (lower(p.display_name) like :search or lower(p.base_asset) like :search ")
                    .append("or lower(p.quote_asset) like :search or exists (")
                    .append("select 1 from venue_instrument vsi where vsi.product_id = p.product_id ")
                    .append("and lower(vsi.symbol) like :search))");
        }
        if (criteria.category() != null) {
            sql.append(" and p.category = :category");
        }
        if (criteria.exchange() != null || criteria.marketType() != null) {
            sql.append(" and exists (select 1 from venue_instrument vf where vf.product_id = p.product_id");
            if (criteria.exchange() != null) {
                sql.append(" and vf.exchange = :exchange");
            }
            if (criteria.marketType() != null) {
                sql.append(" and vf.market_type = :marketType");
            }
            sql.append(" and vf.status = 'ACTIVE')");
        }
        var statement = jdbcClient.sql(sql.toString());
        if (criteria.search() != null) {
            statement = statement.param(
                    "search", "%" + criteria.search().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        if (criteria.category() != null) {
            statement = statement.param("category", criteria.category().name());
        }
        if (criteria.exchange() != null) {
            statement = statement.param("exchange", criteria.exchange().name());
        }
        if (criteria.marketType() != null) {
            statement = statement.param("marketType", criteria.marketType().name());
        }
        return statement.query(Long.class).single();
    }

    private static InstrumentId nullableInstrumentId(String value) {
        return value == null ? null : new InstrumentId(value);
    }

    private static Instant instant(OffsetDateTime value) {
        return Objects.requireNonNull(value, "database timestamp").toInstant();
    }

    private static Instant nullableInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record ProductRoot(
            ProductId productId,
            String baseAsset,
            String quoteAsset,
            String displayName,
            ProductCategory category,
            CatalogStatus status) {
    }

    private record WatchlistRoot(
            String name,
            String description,
            boolean defaultWatchlist,
            long version,
            Instant updatedAt) {
    }
}
