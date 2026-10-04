package io.omnnu.finbot.infrastructure.paper.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.market.dto.ResearchMarketScope;
import io.omnnu.finbot.application.paper.dto.LocalPaperAccount;
import io.omnnu.finbot.application.paper.dto.LocalPaperCandidate;
import io.omnnu.finbot.application.paper.dto.LocalPaperTradePage;
import io.omnnu.finbot.application.paper.exception.LocalPaperConflictException;
import io.omnnu.finbot.application.paper.exception.LocalPaperNotFoundException;
import io.omnnu.finbot.application.paper.port.out.LocalPaperStore;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.paper.PaperMatchingResult;
import io.omnnu.finbot.domain.paper.PaperTrade;
import io.omnnu.finbot.domain.paper.PaperTradeEvent;
import io.omnnu.finbot.domain.paper.PaperTradeTerms;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public final class JdbcLocalPaperStore implements LocalPaperStore {
    private static final String ACCOUNT_ID = "local_paper_bybit_tradfi";
    private static final String ELIGIBLE_INSTRUMENT = """
            instrument.exchange = 'BYBIT' and instrument.market_type = 'LINEAR_PERPETUAL'
            and instrument.settlement_asset = 'USDT' and instrument.status = 'ACTIVE'
            and product.status = 'ACTIVE'
            """;
    private static final String USER_SELECTED_INSTRUMENT = """
            and exists(select 1 from watchlist_item selected
              join watchlist list on list.watchlist_id = selected.watchlist_id
              where list.owner_id = 'admin' and list.is_default
                and selected.research_mode in ('RESEARCH','PINNED')
                and selected.product_id = instrument.product_id
                and selected.preferred_instrument_id = instrument.instrument_id)
            """;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final TransactionTemplate snapshots;

    public JdbcLocalPaperStore(JdbcClient jdbc, ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
        transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        snapshots = new TransactionTemplate(transactionManager);
        snapshots.setReadOnly(true);
        snapshots.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public boolean usesLiveMarket(io.omnnu.finbot.domain.catalog.InstrumentId instrumentId) {
        return jdbc.sql("""
                select exists(select 1 from venue_instrument instrument
                join canonical_product product on product.product_id = instrument.product_id
                where %s and instrument.instrument_id = :instrumentId)
                """.formatted(ELIGIBLE_INSTRUMENT)).param("instrumentId", instrumentId.value()).query(Boolean.class).single();
    }

    @Override
    public boolean supports(ResearchMarketScope scope) {
        if (scope == null || scope.exchange() != ExchangeVenue.BYBIT
                || (scope.environment() != ExchangeEnvironment.LIVE && scope.environment() != ExchangeEnvironment.DEMO)) return false;
        return jdbc.sql("""
                select exists(select 1 from venue_instrument instrument
                join canonical_product product on product.product_id = instrument.product_id
                where %s and instrument.instrument_id = :instrumentId and instrument.symbol = :symbol
                and exists(select 1 from local_paper_account where account_id = :accountId and orders_enabled)
                and exists(select 1 from system_setting where setting_key = 'execution.paper.enabled' and value_text = 'true'))
                """.formatted(ELIGIBLE_INSTRUMENT + USER_SELECTED_INSTRUMENT))
                .param("instrumentId", scope.instrumentId().value()).param("symbol", scope.symbol())
                .param("accountId", ACCOUNT_ID).query(Boolean.class).single();
    }

    @Override
    public LocalPaperAccount account() {
        // Cash and positions must be read from the same database snapshot during a concurrent fill.
        return Objects.requireNonNull(snapshots.execute(status -> accountSnapshot()));
    }

    private LocalPaperAccount accountSnapshot() {
        var root = accountRoot(false);
        var active = activeAccountTrades();
        var reservation = active.stream().map(PaperTrade::reservationUsdt).reduce(BigDecimal.ZERO, BigDecimal::add);
        var unrealized = active.stream().map(PaperTrade::unrealizedPnlUsdt).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new LocalPaperAccount(ACCOUNT_ID, "USDT", "LOCAL_PAPER", root.initialBalance(), root.cashBalance(),
                root.cashBalance().add(unrealized),
                root.cashBalance().add(unrealized.min(BigDecimal.ZERO)).subtract(reservation).max(BigDecimal.ZERO),
                reservation, unrealized, root.cashBalance().subtract(root.initialBalance()), root.fees(), root.funding(),
                root.enabled(), (int) active.stream().filter(t -> t.status() == PaperTrade.Status.PENDING_ENTRY).count(),
                (int) active.stream().filter(t -> t.status() == PaperTrade.Status.OPEN).count(),
                root.acceptAfter(), root.checkedAt(), root.version());
    }

    @Override
    public LocalPaperAccount setOrdersEnabled(boolean enabled, long expectedVersion, Instant now) {
        var updated = jdbc.sql("""
                update local_paper_account set orders_enabled = :enabled,
                    accept_projections_after = case when :enabled and not orders_enabled then :now else accept_projections_after end,
                    version = version + 1, updated_at = :now where account_id = :accountId and version = :version
                """).param("enabled", enabled).param("now", Timestamp.from(now))
                .param("accountId", ACCOUNT_ID).param("version", expectedVersion).update();
        if (updated != 1) throw new LocalPaperConflictException("本地模拟账户已变化，请刷新后重试");
        return account();
    }

    @Override
    public LocalPaperTradePage trades(int limit, String beforeTradeId) {
        var size = Math.max(1, Math.min(100, limit));
        PaperTrade cursor = beforeTradeId == null ? null : find(beforeTradeId)
                .orElseThrow(() -> new LocalPaperNotFoundException("本地模拟分页游标不存在"));
        var rows = jdbc.sql("""
                select state from local_paper_trade where account_id = :accountId
                  and (:beforeTime is null or (created_at,trade_id) < (:beforeTime,:beforeId))
                order by created_at desc,trade_id desc limit :limit
                """).param("accountId", ACCOUNT_ID)
                .param("beforeTime", cursor == null ? null : Timestamp.from(cursor.createdAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("beforeId", beforeTradeId, java.sql.Types.VARCHAR).param("limit", size + 1)
                .query((rs, row) -> read(rs.getString("state"), PaperTrade.class)).list();
        var page = rows.stream().limit(size).toList();
        return new LocalPaperTradePage(page, rows.size() > size ? page.getLast().tradeId() : null);
    }

    @Override
    public Optional<PaperTrade> find(String tradeId) {
        return jdbc.sql("select state from local_paper_trade where trade_id = :tradeId and account_id = :accountId")
                .param("tradeId", tradeId).param("accountId", ACCOUNT_ID)
                .query((rs, row) -> read(rs.getString("state"), PaperTrade.class)).optional();
    }

    @Override
    public List<PaperTradeEvent> events(String tradeId, int limit) {
        return jdbc.sql("select event from local_paper_event where trade_id = :tradeId order by occurred_at desc,event_id desc limit :limit")
                .param("tradeId", tradeId).param("limit", Math.max(1, Math.min(200, limit)))
                .query((rs, row) -> read(rs.getString("event"), PaperTradeEvent.class)).list();
    }

    @Override
    public List<LocalPaperCandidate> candidates(Instant now, int limit) {
        return jdbc.sql("""
                select projection.*, instrument.price_tick, policy.taker_fee_rate, policy.slippage_rate, policy.liquidation_buffer_rate,
                    policy.maximum_open_positions,
                    coalesce(forecast.target_at,projection.calculated_at + interval '24 hours') as expires_at
                from estimated_trade_projection projection
                join venue_instrument instrument on instrument.instrument_id = projection.instrument_id
                join canonical_product product on product.product_id = instrument.product_id
                join trade_automation_run automation on automation.automation_run_id = projection.automation_run_id
                join risk_policy policy on policy.policy_version = projection.policy_version
                join local_paper_account account on account.account_id = :accountId
                left join lateral (select target_at from research_forecast where workflow_run_id = projection.workflow_run_id
                    order by issued_at desc,id desc limit 1) forecast on true
                where %s and automation.status = 'ESTIMATED' and account.orders_enabled
                  and projection.calculated_at >= account.accept_projections_after and projection.calculated_at <= :now
                  and coalesce(forecast.target_at,projection.calculated_at + interval '24 hours') > :now
                  and not exists(select 1 from local_paper_trade trade where trade.projection_id = projection.projection_id)
                  and exists(select 1 from system_setting where setting_key = 'execution.paper.enabled' and value_text = 'true')
                order by projection.calculated_at,projection.projection_id limit :limit
                """.formatted(ELIGIBLE_INSTRUMENT + USER_SELECTED_INSTRUMENT))
                .param("accountId", ACCOUNT_ID).param("now", Timestamp.from(now)).param("limit", Math.max(1, Math.min(100, limit)))
                .query((rs, row) -> new LocalPaperCandidate(rs.getString("projection_id"), rs.getString("instrument_id"),
                        rs.getString("symbol"), new PaperTradeTerms(DirectionalAction.valueOf(rs.getString("side")),
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("contract_size"),
                        rs.getBigDecimal("entry_reference").divide(rs.getBigDecimal("price_tick"), 0,
                            "BUY".equals(rs.getString("side")) ? java.math.RoundingMode.FLOOR : java.math.RoundingMode.CEILING)
                            .multiply(rs.getBigDecimal("price_tick")),
                        rs.getBigDecimal("target_price"), rs.getBigDecimal("stop_price"), rs.getBigDecimal("leverage"),
                        rs.getBigDecimal("taker_fee_rate"), rs.getBigDecimal("slippage_rate"), rs.getBigDecimal("liquidation_buffer_rate"),
                        rs.getString("policy_version"), rs.getTimestamp("expires_at").toInstant()),
                        rs.getInt("maximum_open_positions"), rs.getTimestamp("calculated_at").toInstant())).list();
    }

    @Override
    public boolean reserve(LocalPaperCandidate candidate, PaperTrade trade, Instant now) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var account = accountRoot(true);
            if (!account.enabled() || candidate.calculatedAt().isBefore(account.acceptAfter())) return false;
            if (!supportsInstrument(trade.instrumentId(), trade.symbol())) return false;
            if (!trade.projectionId().equals(candidate.projectionId()) || !trade.terms().equals(candidate.terms())
                    || !trade.instrumentId().equals(candidate.instrumentId()) || !trade.symbol().equals(candidate.symbol())
                    || trade.status() != PaperTrade.Status.PENDING_ENTRY || trade.version() != 0
                    || !trade.terms().expiresAt().isAfter(now)) throw new IllegalArgumentException("Invalid paper admission");
            if (find(trade.tradeId()).isPresent()) return false;
            var active = activeAccountTrades();
            if (active.size() >= candidate.maximumOpenPositions()
                    || active.stream().anyMatch(t -> t.instrumentId().equals(trade.instrumentId()))) return false;
            if (active.stream().filter(t -> t.status() == PaperTrade.Status.OPEN)
                    .anyMatch(t -> t.quoteAt() == null || t.quoteAt().isBefore(now.minusSeconds(30))
                            || t.healthCode() != PaperTrade.Health.READY)) return false;
            var occupied = active.stream().map(PaperTrade::reservationUsdt).reduce(BigDecimal.ZERO, BigDecimal::add);
            var unrealized = active.stream().map(PaperTrade::unrealizedPnlUsdt).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (account.cashBalance().add(unrealized.min(BigDecimal.ZERO)).subtract(occupied).compareTo(trade.reservationUsdt()) < 0) return false;
            jdbc.sql("""
                    insert into local_paper_trade(trade_id,account_id,projection_id,instrument_id,symbol,status,state,version,created_at,updated_at)
                    values(:tradeId,:accountId,:projectionId,:instrumentId,:symbol,:status,cast(:state as jsonb),:version,:createdAt,:now)
                    """).param("tradeId", trade.tradeId()).param("accountId", ACCOUNT_ID).param("projectionId", trade.projectionId())
                    .param("instrumentId", trade.instrumentId()).param("symbol", trade.symbol()).param("status", trade.status().name())
                    .param("state", write(trade)).param("version", trade.version()).param("createdAt", Timestamp.from(trade.createdAt()))
                    .param("now", Timestamp.from(now)).update();
            insertEvent(new PaperTradeEvent(trade.tradeId() + "_reserved", trade.tradeId(), PaperTradeEvent.Type.RESERVED,
                    now, null, BigDecimal.ZERO, BigDecimal.ZERO, "LOCAL_PAPER_V1:RISK_RESERVATION", "LOCAL"), now);
            return true;
        }));
    }

    @Override
    public List<PaperTrade> active(int limit) {
        return jdbc.sql("""
                select state from local_paper_trade where account_id = :accountId and status in ('PENDING_ENTRY','OPEN')
                order by updated_at,trade_id limit :limit
                """).param("accountId", ACCOUNT_ID).param("limit", Math.max(1, Math.min(100, limit)))
                .query((rs, row) -> read(rs.getString("state"), PaperTrade.class)).list();
    }

    @Override
    public PaperTrade apply(long expectedVersion, PaperMatchingResult result, Instant now) {
        return Objects.requireNonNull(transactions.execute(status -> {
            accountRoot(true);
            var previous = find(result.trade().tradeId()).orElseThrow(() -> new LocalPaperNotFoundException("本地模拟交易不存在"));
            if (previous.version() != expectedVersion) throw new LocalPaperConflictException("本地模拟交易已被撮合，请刷新后重试");
            if (!previous.terms().equals(result.trade().terms()) || !previous.projectionId().equals(result.trade().projectionId())
                    || !previous.instrumentId().equals(result.trade().instrumentId())) throw new IllegalArgumentException("Paper trade frozen terms changed");
            for (var event : result.events()) insertEvent(event, now);
            var fees = result.events().stream().map(PaperTradeEvent::feeUsdt).reduce(BigDecimal.ZERO, BigDecimal::add);
            var funding = result.events().stream().filter(e -> e.type() == PaperTradeEvent.Type.FUNDING)
                    .map(e -> e.cashDeltaUsdt().negate()).reduce(BigDecimal.ZERO, BigDecimal::add);
            jdbc.sql("""
                    update local_paper_account set cash_balance = cash_balance + :cash, fees_usdt = fees_usdt + :fees,
                        funding_usdt = funding_usdt + :funding, updated_at = :now where account_id = :accountId
                    """).param("cash", result.cashDeltaUsdt()).param("fees", fees).param("funding", funding)
                    .param("now", Timestamp.from(now)).param("accountId", ACCOUNT_ID).update();
            writeTrade(result.trade(), expectedVersion, now);
            return result.trade();
        }));
    }

    @Override
    public void marketUnavailable(String tradeId, long expectedVersion, Instant now) {
        transactions.executeWithoutResult(status -> {
            accountRoot(true);
            var previous = find(tradeId).orElse(null);
            if (previous == null || previous.version() != expectedVersion || !previous.active()) return;
            var failed = new PaperTrade(previous.tradeId(), previous.projectionId(), previous.instrumentId(), previous.symbol(), previous.terms(),
                    previous.status(), previous.createdAt(), previous.enteredAt(), previous.entryPrice(), previous.closedAt(), previous.exitPrice(),
                    previous.candleCursor(), previous.fundingCursor(), previous.quoteAt(), previous.markPrice(), previous.feesUsdt(), previous.fundingUsdt(),
                    previous.realizedPnlUsdt(), PaperTrade.Health.MARKET_UNAVAILABLE, previous.version() + 1);
            writeTrade(failed, expectedVersion, now);
        });
    }

    @Override
    public void checkedAt(Instant now) {
        jdbc.sql("update local_paper_account set last_checked_at = :now where account_id = :accountId")
                .param("now", Timestamp.from(now)).param("accountId", ACCOUNT_ID).update();
    }

    private void writeTrade(PaperTrade trade, long expectedVersion, Instant now) {
        var count = jdbc.sql("""
                update local_paper_trade set status = :status, state = cast(:state as jsonb),version = :newVersion,updated_at = :now
                where trade_id = :tradeId and version = :expectedVersion
                """).param("status", trade.status().name()).param("state", write(trade)).param("newVersion", trade.version())
                .param("now", Timestamp.from(now)).param("tradeId", trade.tradeId()).param("expectedVersion", expectedVersion).update();
        if (count != 1) throw new LocalPaperConflictException("本地模拟交易版本冲突");
    }

    private void insertEvent(PaperTradeEvent event, Instant now) {
        var inserted = jdbc.sql("""
                insert into local_paper_event(event_id,trade_id,event_type,occurred_at,cash_delta_usdt,event,recorded_at)
                values(:eventId,:tradeId,:type,:occurredAt,:cash,cast(:event as jsonb),:now) on conflict do nothing
                """).param("eventId", event.eventId()).param("tradeId", event.tradeId()).param("type", event.type().name())
                .param("occurredAt", Timestamp.from(event.occurredAt())).param("cash", event.cashDeltaUsdt())
                .param("event", write(event)).param("now", Timestamp.from(now)).update();
        if (inserted != 1) throw new LocalPaperConflictException("模拟成交或费用事件已存在，拒绝重复记账");
    }

    private List<PaperTrade> activeAccountTrades() {
        return active(100);
    }

    private AccountRoot accountRoot(boolean lock) {
        return jdbc.sql("select initial_balance,cash_balance,fees_usdt,funding_usdt,orders_enabled,accept_projections_after,last_checked_at,version "
                + "from local_paper_account where account_id = :accountId" + (lock ? " for update" : ""))
                .param("accountId", ACCOUNT_ID).query((rs, row) -> root(rs)).single();
    }

    private boolean supportsInstrument(String instrumentId, String symbol) {
        return jdbc.sql("""
                select exists(select 1 from venue_instrument instrument
                join canonical_product product on product.product_id = instrument.product_id
                where %s and instrument.instrument_id = :instrumentId and instrument.symbol = :symbol
                and exists(select 1 from system_setting where setting_key = 'execution.paper.enabled' and value_text = 'true'))
                """.formatted(ELIGIBLE_INSTRUMENT + USER_SELECTED_INSTRUMENT)).param("instrumentId", instrumentId).param("symbol", symbol)
                .query(Boolean.class).single();
    }

    private static AccountRoot root(ResultSet rs) throws SQLException {
        var checked = rs.getTimestamp("last_checked_at");
        return new AccountRoot(rs.getBigDecimal("initial_balance"), rs.getBigDecimal("cash_balance"), rs.getBigDecimal("fees_usdt"),
                rs.getBigDecimal("funding_usdt"), rs.getBoolean("orders_enabled"), rs.getTimestamp("accept_projections_after").toInstant(),
                checked == null ? null : checked.toInstant(), rs.getLong("version"));
    }

    private record AccountRoot(BigDecimal initialBalance, BigDecimal cashBalance, BigDecimal fees, BigDecimal funding,
                               boolean enabled, Instant acceptAfter, Instant checkedAt, long version) { }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Unable to encode paper state", exception); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid persisted paper state", exception); }
    }
}
