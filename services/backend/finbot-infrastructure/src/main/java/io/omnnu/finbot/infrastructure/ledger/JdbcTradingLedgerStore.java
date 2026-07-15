package io.omnnu.finbot.infrastructure.ledger;

import static io.omnnu.finbot.infrastructure.jdbc.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.ledger.AccountLedgerProjection;
import io.omnnu.finbot.application.ledger.AccountSnapshotFact;
import io.omnnu.finbot.application.ledger.BalanceFact;
import io.omnnu.finbot.application.ledger.FillFact;
import io.omnnu.finbot.application.ledger.OrderFact;
import io.omnnu.finbot.application.ledger.PositionSnapshotFact;
import io.omnnu.finbot.application.ledger.PositionView;
import io.omnnu.finbot.application.ledger.RealizedPnlFact;
import io.omnnu.finbot.application.ledger.TradingActivity;
import io.omnnu.finbot.application.ledger.TradingActivityCount;
import io.omnnu.finbot.application.ledger.TradingActivityCriteria;
import io.omnnu.finbot.application.ledger.TradingActivityCursor;
import io.omnnu.finbot.application.ledger.TradingActivityPage;
import io.omnnu.finbot.application.ledger.TradingActivitySourceStatus;
import io.omnnu.finbot.application.ledger.TradingLedgerQueryRepository;
import io.omnnu.finbot.application.ledger.TradingLedgerWriter;
import io.omnnu.finbot.application.ledger.TradingTimeRange;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.ledger.PositionSide;
import io.omnnu.finbot.domain.ledger.TradingActivitySource;
import io.omnnu.finbot.domain.ledger.TradingActivityType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcTradingLedgerStore implements TradingLedgerWriter, TradingLedgerQueryRepository {
    private final JdbcClient jdbcClient;

    public JdbcTradingLedgerStore(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    public boolean appendAccountSnapshot(AccountSnapshotFact snapshot) {
        return jdbcClient.sql("""
                insert into exchange_account_snapshot (
                  snapshot_id, account_id, source_event_id, currency, equity,
                  available_balance, margin_balance, unrealized_pnl, occurred_at, received_at
                ) values (
                  :snapshotId, :accountId, :sourceEventId, :currency, :equity,
                  :availableBalance, :marginBalance, :unrealizedPnl, :occurredAt, :receivedAt
                ) on conflict (account_id, source_event_id) do nothing
                """)
                .param("snapshotId", snapshot.snapshotId().value())
                .param("accountId", snapshot.accountId().value())
                .param("sourceEventId", snapshot.sourceEventId())
                .param("currency", snapshot.equity().currency())
                .param("equity", snapshot.equity().amount())
                .param("availableBalance", snapshot.availableBalance().amount())
                .param("marginBalance", snapshot.marginBalance().amount())
                .param("unrealizedPnl", snapshot.unrealizedPnl().amount())
                .param("occurredAt", timestamp(snapshot.occurredAt()))
                .param("receivedAt", timestamp(snapshot.receivedAt()))
                .update() == 1;
    }

    @Override
    public boolean appendBalance(BalanceFact balance) {
        return jdbcClient.sql("""
                insert into exchange_balance_fact (
                  fact_id, account_id, source_event_id, currency, total, available,
                  change_amount, reason, occurred_at, received_at
                ) values (
                  :factId, :accountId, :sourceEventId, :currency, :total, :available,
                  :changeAmount, :reason, :occurredAt, :receivedAt
                ) on conflict (account_id, source_event_id) do nothing
                """)
                .param("factId", balance.factId().value())
                .param("accountId", balance.accountId().value())
                .param("sourceEventId", balance.sourceEventId())
                .param("currency", balance.currency())
                .param("total", balance.total())
                .param("available", balance.available())
                .param("changeAmount", balance.changeAmount())
                .param("reason", balance.reason().name())
                .param("occurredAt", timestamp(balance.occurredAt()))
                .param("receivedAt", timestamp(balance.receivedAt()))
                .update() == 1;
    }

    @Override
    public boolean appendOrder(OrderFact order) {
        return jdbcClient.sql("""
                insert into exchange_order_fact (
                  fact_id, account_id, source_event_id, exchange_order_id, client_order_id,
                  symbol, side, order_type, status, quantity, filled_quantity, limit_price,
                  average_fill_price, reduce_only, occurred_at, received_at
                ) values (
                  :factId, :accountId, :sourceEventId, :exchangeOrderId, :clientOrderId,
                  :symbol, :side, :orderType, :status, :quantity, :filledQuantity, :limitPrice,
                  :averageFillPrice, :reduceOnly, :occurredAt, :receivedAt
                ) on conflict do nothing
                """)
                .param("factId", order.factId().value())
                .param("accountId", order.accountId().value())
                .param("sourceEventId", order.sourceEventId())
                .param("exchangeOrderId", order.exchangeOrderId())
                .param("clientOrderId", order.clientOrderId())
                .param("symbol", order.symbol().value())
                .param("side", order.side().name())
                .param("orderType", order.orderType().name())
                .param("status", order.status().name())
                .param("quantity", order.quantity())
                .param("filledQuantity", order.filledQuantity())
                .param("limitPrice", order.limitPrice())
                .param("averageFillPrice", order.averageFillPrice())
                .param("reduceOnly", order.reduceOnly())
                .param("occurredAt", timestamp(order.occurredAt()))
                .param("receivedAt", timestamp(order.receivedAt()))
                .update() == 1;
    }

    @Override
    public boolean appendFill(FillFact fill) {
        return jdbcClient.sql("""
                insert into exchange_fill_fact (
                  fact_id, account_id, source_event_id, exchange_fill_id, exchange_order_id,
                  client_order_id, symbol, side, quantity, price, fee, fee_currency,
                  realized_pnl, occurred_at, received_at
                ) values (
                  :factId, :accountId, :sourceEventId, :exchangeFillId, :exchangeOrderId,
                  :clientOrderId, :symbol, :side, :quantity, :price, :fee, :feeCurrency,
                  :realizedPnl, :occurredAt, :receivedAt
                ) on conflict do nothing
                """)
                .param("factId", fill.factId().value())
                .param("accountId", fill.accountId().value())
                .param("sourceEventId", fill.sourceEventId())
                .param("exchangeFillId", fill.exchangeFillId())
                .param("exchangeOrderId", fill.exchangeOrderId())
                .param("clientOrderId", fill.clientOrderId())
                .param("symbol", fill.symbol().value())
                .param("side", fill.side().name())
                .param("quantity", fill.quantity())
                .param("price", fill.price())
                .param("fee", fill.fee())
                .param("feeCurrency", fill.feeCurrency())
                .param("realizedPnl", fill.realizedPnl())
                .param("occurredAt", timestamp(fill.occurredAt()))
                .param("receivedAt", timestamp(fill.receivedAt()))
                .update() == 1;
    }

    @Override
    public boolean appendPosition(PositionSnapshotFact position) {
        return jdbcClient.sql("""
                insert into exchange_position_snapshot (
                  snapshot_id, account_id, source_event_id, symbol, side, quantity,
                  entry_price, mark_price, liquidation_price, leverage, unrealized_pnl,
                  margin, occurred_at, received_at
                ) values (
                  :snapshotId, :accountId, :sourceEventId, :symbol, :side, :quantity,
                  :entryPrice, :markPrice, :liquidationPrice, :leverage, :unrealizedPnl,
                  :margin, :occurredAt, :receivedAt
                ) on conflict (account_id, source_event_id) do nothing
                """)
                .param("snapshotId", position.snapshotId().value())
                .param("accountId", position.accountId().value())
                .param("sourceEventId", position.sourceEventId())
                .param("symbol", position.symbol().value())
                .param("side", position.side().name())
                .param("quantity", position.quantity())
                .param("entryPrice", position.entryPrice())
                .param("markPrice", position.markPrice())
                .param("liquidationPrice", position.liquidationPrice())
                .param("leverage", position.leverage())
                .param("unrealizedPnl", position.unrealizedPnl())
                .param("margin", position.margin())
                .param("occurredAt", timestamp(position.occurredAt()))
                .param("receivedAt", timestamp(position.receivedAt()))
                .update() == 1;
    }

    @Override
    public boolean appendRealizedPnl(RealizedPnlFact pnl) {
        return jdbcClient.sql("""
                insert into realized_pnl_fact (
                  fact_id, account_id, source_event_id, symbol, amount, currency,
                  source_type, related_order_id, related_fill_id, occurred_at, received_at
                ) values (
                  :factId, :accountId, :sourceEventId, :symbol, :amount, :currency,
                  :sourceType, :relatedOrderId, :relatedFillId, :occurredAt, :receivedAt
                ) on conflict (account_id, source_event_id) do nothing
                """)
                .param("factId", pnl.factId().value())
                .param("accountId", pnl.accountId().value())
                .param("sourceEventId", pnl.sourceEventId())
                .param("symbol", pnl.symbol().value())
                .param("amount", pnl.amount().amount())
                .param("currency", pnl.amount().currency())
                .param("sourceType", pnl.sourceType().name())
                .param("relatedOrderId", pnl.relatedOrderId())
                .param("relatedFillId", pnl.relatedFillId())
                .param("occurredAt", timestamp(pnl.occurredAt()))
                .param("receivedAt", timestamp(pnl.receivedAt()))
                .update() == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountLedgerProjection> accountOverview(TradingTimeRange range) {
        return jdbcClient.sql("""
                select a.account_id, a.exchange, a.environment, a.display_name,
                       a.api_key_env, a.api_secret_env, a.proxy_route, a.enabled,
                       a.version,
                       coalesce(s.currency, 'USDT') as currency,
                       coalesce(s.equity, 0) as equity,
                       coalesce(s.available_balance, 0) as available_balance,
                       coalesce(s.margin_balance, 0) as margin_balance,
                       coalesce(s.unrealized_pnl, 0) as unrealized_pnl,
                       coalesce((
                         select sum(pnl.amount) from realized_pnl_fact pnl
                         where pnl.account_id = a.account_id
                           and pnl.currency = coalesce(s.currency, 'USDT')
                           and pnl.occurred_at >= :fromInclusive
                           and pnl.occurred_at < :toExclusive
                       ), 0) as realized_pnl,
                       coalesce((
                         select count(*) from (
                           select distinct on (ps.symbol) ps.quantity
                           from exchange_position_snapshot ps
                           where ps.account_id = a.account_id
                           order by ps.symbol, ps.occurred_at desc, ps.id desc
                         ) current_position where current_position.quantity > 0
                       ), 0) as open_position_count,
                       s.occurred_at as snapshot_at
                from exchange_account a
                left join lateral (
                  select currency, equity, available_balance, margin_balance,
                         unrealized_pnl, occurred_at
                  from exchange_account_snapshot latest
                  where latest.account_id = a.account_id
                  order by latest.occurred_at desc, latest.id desc
                  limit 1
                ) s on true
                order by a.exchange, a.environment, a.account_id
                """)
                .param("fromInclusive", timestamp(range.fromInclusive()))
                .param("toExclusive", timestamp(range.toExclusive()))
                .query((resultSet, rowNumber) -> accountProjection(resultSet))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PositionView> currentPositions(ExchangeAccountId accountId) {
        return jdbcClient.sql("""
                select account_id, symbol, side, quantity, entry_price, mark_price,
                       liquidation_price, leverage, unrealized_pnl, margin, occurred_at
                from (
                  select distinct on (symbol)
                         account_id, symbol, side, quantity, entry_price, mark_price,
                         liquidation_price, leverage, unrealized_pnl, margin, occurred_at, id
                  from exchange_position_snapshot
                  where account_id = :accountId
                  order by symbol, occurred_at desc, id desc
                ) current_position
                where quantity > 0
                order by symbol
                """)
                .param("accountId", accountId.value())
                .query((resultSet, rowNumber) -> new PositionView(
                        new ExchangeAccountId(resultSet.getString("account_id")),
                        resultSet.getString("symbol"),
                        PositionSide.valueOf(resultSet.getString("side")),
                        resultSet.getBigDecimal("quantity"),
                        resultSet.getBigDecimal("entry_price"),
                        resultSet.getBigDecimal("mark_price"),
                        resultSet.getBigDecimal("liquidation_price"),
                        resultSet.getBigDecimal("leverage"),
                        resultSet.getBigDecimal("unrealized_pnl"),
                        resultSet.getBigDecimal("margin"),
                        instant(resultSet.getObject("occurred_at", OffsetDateTime.class))))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public TradingActivityPage activity(TradingActivityCriteria criteria) {
        var pageSql = """
                select activity_id, source_event_id, activity_type, source,
                       account_id, exchange, symbol, status, side, quantity, price,
                       amount, currency, exchange_order_id, client_order_id,
                       title, detail, details::text as details_json,
                       occurred_at, received_at
                from trading_activity_projection
                """ + activityWhere(criteria, true)
                + " order by occurred_at desc, activity_id desc limit :limit";
        var rows = new ArrayList<>(bindActivityCriteria(
                        jdbcClient.sql(pageSql),
                        criteria,
                        true)
                .param("limit", criteria.limit() + 1)
                .query((resultSet, rowNumber) -> activity(resultSet))
                .list());
        var hasMore = rows.size() > criteria.limit();
        if (hasMore) {
            rows.removeLast();
        }
        var next = hasMore && !rows.isEmpty()
                ? new TradingActivityCursor(rows.getLast().occurredAt(), rows.getLast().activityId())
                : null;
        var counts = bindActivityCriteria(
                        jdbcClient.sql("""
                                select activity_type, count(*) as activity_count
                                from trading_activity_projection
                                """ + activityWhere(criteria, false)
                                + " group by activity_type order by activity_type"),
                        criteria,
                        false)
                .query((resultSet, rowNumber) -> new TradingActivityCount(
                        TradingActivityType.valueOf(resultSet.getString("activity_type")),
                        resultSet.getLong("activity_count")))
                .list();
        var matchedCount = counts.stream().mapToLong(TradingActivityCount::count).sum();
        return new TradingActivityPage(rows, next, matchedCount, counts, activitySources());
    }

    private static String activityWhere(TradingActivityCriteria criteria, boolean includeCursor) {
        var where = new StringBuilder(
                " where occurred_at >= :fromInclusive and occurred_at < :toExclusive");
        if (criteria.accountId() != null) {
            where.append(" and account_id = :accountId");
        }
        if (criteria.source() != null) {
            where.append(" and source = :source");
        }
        if (criteria.activityType() != null) {
            where.append(" and activity_type = :activityType");
        }
        if (criteria.status() != null) {
            where.append(" and upper(status) = upper(:status)");
        }
        if (criteria.symbol() != null) {
            where.append(" and upper(symbol) like '%' || upper(:symbol) || '%'");
        }
        if (includeCursor && criteria.before() != null) {
            where.append(" and (occurred_at, activity_id) < (:beforeOccurredAt, :beforeActivityId)");
        }
        return where.toString();
    }

    private static JdbcClient.StatementSpec bindActivityCriteria(
            JdbcClient.StatementSpec statement,
            TradingActivityCriteria criteria,
            boolean includeCursor) {
        var bound = statement
                .param("fromInclusive", timestamp(criteria.range().fromInclusive()))
                .param("toExclusive", timestamp(criteria.range().toExclusive()));
        if (criteria.accountId() != null) {
            bound = bound.param("accountId", criteria.accountId().value());
        }
        if (criteria.source() != null) {
            bound = bound.param("source", criteria.source().name());
        }
        if (criteria.activityType() != null) {
            bound = bound.param("activityType", criteria.activityType().name());
        }
        if (criteria.status() != null) {
            bound = bound.param("status", criteria.status());
        }
        if (criteria.symbol() != null) {
            bound = bound.param("symbol", criteria.symbol());
        }
        if (includeCursor && criteria.before() != null) {
            bound = bound
                    .param("beforeOccurredAt", timestamp(criteria.before().occurredAt()))
                    .param("beforeActivityId", criteria.before().activityId());
        }
        return bound;
    }

    private List<TradingActivitySourceStatus> activitySources() {
        return jdbcClient.sql("""
                select source, account_id, exchange, status, complete, message, latest_at
                from (
                  select 'LOCAL_OMS'::varchar as source, null::varchar as account_id,
                         null::varchar as exchange, 'READY'::varchar as status,
                         true as complete, '本地决策、风控、预估和 OMS 永久审计可用'::text as message,
                         (select max(occurred_at) from trading_activity_projection
                          where source = 'LOCAL_OMS') as latest_at,
                         0 as source_order
                  union all
                  select 'EXCHANGE', account.account_id, account.exchange,
                         case
                           when not account.enabled then 'DISABLED'
                           when reconciliation.status is not null then reconciliation.status
                           when latest_activity.latest_at is not null then 'READY'
                           else 'NOT_SYNCED'
                         end,
                         account.enabled and reconciliation.status = 'COMPLETED',
                         case
                           when not account.enabled then '账户已停用；保留既有交易所事实'
                           when reconciliation.status = 'COMPLETED' then '最近一次交易所对账完成'
                           when reconciliation.status = 'PARTIAL' then coalesce(reconciliation.error_message, '最近一次交易所对账部分完成')
                           when reconciliation.status = 'FAILED' then coalesce(reconciliation.error_message, '最近一次交易所对账失败')
                           when latest_activity.latest_at is not null then '已保存交易所事实，尚无完成的对账记录'
                           else '尚未同步到交易所账户事实'
                         end,
                         greatest(reconciliation.completed_at, reconciliation.started_at, latest_activity.latest_at),
                         1
                  from exchange_account account
                  left join lateral (
                    select run.status, run.error_message, run.started_at, run.completed_at
                    from exchange_reconciliation_run run
                    where run.account_id = account.account_id
                    order by run.started_at desc, run.id desc
                    limit 1
                  ) reconciliation on true
                  left join lateral (
                    select max(activity.occurred_at) as latest_at
                    from trading_activity_projection activity
                    where activity.source = 'EXCHANGE'
                      and activity.account_id = account.account_id
                  ) latest_activity on true
                ) source_status
                order by source_order, exchange nulls first, account_id nulls first
                """)
                .query((resultSet, rowNumber) -> {
                    var accountId = resultSet.getString("account_id");
                    var exchange = resultSet.getString("exchange");
                    return new TradingActivitySourceStatus(
                            TradingActivitySource.valueOf(resultSet.getString("source")),
                            accountId == null ? null : new ExchangeAccountId(accountId),
                            exchange == null ? null : ExchangeVenue.valueOf(exchange),
                            resultSet.getString("status"),
                            resultSet.getBoolean("complete"),
                            resultSet.getString("message"),
                            nullableInstant(resultSet.getObject("latest_at", OffsetDateTime.class)));
                })
                .list();
    }

    private static AccountLedgerProjection accountProjection(ResultSet resultSet) throws SQLException {
        return new AccountLedgerProjection(
                new ExchangeAccountId(resultSet.getString("account_id")),
                ExchangeVenue.valueOf(resultSet.getString("exchange")),
                ExchangeEnvironment.valueOf(resultSet.getString("environment")),
                resultSet.getString("display_name"),
                resultSet.getString("api_key_env"),
                resultSet.getString("api_secret_env"),
                resultSet.getString("proxy_route"),
                resultSet.getBoolean("enabled"),
                resultSet.getLong("version"),
                resultSet.getString("currency"),
                resultSet.getBigDecimal("equity"),
                resultSet.getBigDecimal("available_balance"),
                resultSet.getBigDecimal("margin_balance"),
                resultSet.getBigDecimal("unrealized_pnl"),
                resultSet.getBigDecimal("realized_pnl"),
                resultSet.getInt("open_position_count"),
                nullableInstant(resultSet.getObject("snapshot_at", OffsetDateTime.class)));
    }

    private static TradingActivity activity(ResultSet resultSet) throws SQLException {
        var accountId = resultSet.getString("account_id");
        var exchange = resultSet.getString("exchange");
        return new TradingActivity(
                resultSet.getString("activity_id"),
                resultSet.getString("source_event_id"),
                TradingActivityType.valueOf(resultSet.getString("activity_type")),
                TradingActivitySource.valueOf(resultSet.getString("source")),
                accountId == null ? null : new ExchangeAccountId(accountId),
                exchange == null ? null : ExchangeVenue.valueOf(exchange),
                resultSet.getString("symbol"),
                resultSet.getString("status"),
                resultSet.getString("side"),
                resultSet.getBigDecimal("quantity"),
                resultSet.getBigDecimal("price"),
                resultSet.getBigDecimal("amount"),
                resultSet.getString("currency"),
                resultSet.getString("exchange_order_id"),
                resultSet.getString("client_order_id"),
                resultSet.getString("title"),
                resultSet.getString("detail"),
                resultSet.getString("details_json"),
                instant(resultSet.getObject("occurred_at", OffsetDateTime.class)),
                instant(resultSet.getObject("received_at", OffsetDateTime.class)));
    }

    private static Instant instant(OffsetDateTime value) {
        return Objects.requireNonNull(value, "database timestamp").toInstant();
    }

    private static Instant nullableInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
