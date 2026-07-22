package io.omnnu.finbot.infrastructure.exchange.persistence;

import static io.omnnu.finbot.infrastructure.jdbc.persistence.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.exchange.dto.OmsReconciliationCandidate;
import io.omnnu.finbot.application.exchange.port.out.OrderReconciliationStore;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.oms.OrderId;
import io.omnnu.finbot.domain.oms.OrderStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcOrderReconciliationStore implements OrderReconciliationStore {
    private final JdbcClient jdbcClient;

    public JdbcOrderReconciliationStore(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    public void start(String reconciliationId, ExchangeAccountId accountId, Instant startedAt) {
        jdbcClient.sql("""
                insert into exchange_reconciliation_run (
                  reconciliation_id, account_id, status, started_at
                ) values (:reconciliationId, :accountId, 'RUNNING', :startedAt)
                """)
                .param("reconciliationId", reconciliationId)
                .param("accountId", accountId.value())
                .param("startedAt", timestamp(startedAt))
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderId> recoverableOrders(ExchangeAccountId accountId, int limit) {
        return jdbcClient.sql("""
                select order_id
                from oms_order
                where account_ref = :accountId
                  and (
                    status = 'PLANNED'
                    or (status = 'SUBMITTING' and submission_claim_until <= current_timestamp)
                  )
                order by created_at, id
                limit :limit
                """)
                .param("accountId", accountId.value())
                .param("limit", limit)
                .query(String.class)
                .list()
                .stream()
                .map(OrderId::new)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OmsReconciliationCandidate> candidates(ExchangeAccountId accountId, int limit) {
        return jdbcClient.sql("""
                select local.order_id, local.status as local_status,
                       exchange_fact.status as exchange_status,
                       exchange_fact.exchange_order_id,
                       least(local.requested_quantity, exchange_fact.filled_quantity) as filled_quantity,
                       coalesce(exchange_fact.average_fill_price, latest_fill.price) as average_fill_price,
                       exchange_fact.occurred_at
                from oms_order local
                join lateral (
                  select fact.status, fact.exchange_order_id, fact.filled_quantity,
                         fact.average_fill_price, fact.occurred_at
                  from exchange_order_fact fact
                  where fact.account_id = local.account_ref
                    and fact.status <> 'UNKNOWN'
                    and (
                      (local.exchange_order_id is not null
                        and fact.exchange_order_id = local.exchange_order_id)
                      or (local.client_order_id is not null
                        and fact.client_order_id = local.client_order_id)
                    )
                  order by fact.occurred_at desc, fact.id desc
                  limit 1
                ) exchange_fact on true
                left join lateral (
                  select fill.price
                  from exchange_fill_fact fill
                  where fill.account_id = local.account_ref
                    and fill.exchange_order_id = exchange_fact.exchange_order_id
                  order by fill.occurred_at desc, fill.id desc
                  limit 1
                ) latest_fill on true
                where local.account_ref = :accountId
                  and local.status in ('SUBMITTING', 'SUBMITTED', 'PARTIALLY_FILLED')
                order by local.updated_at, local.id
                limit :limit
                """)
                .param("accountId", accountId.value())
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new OmsReconciliationCandidate(
                        new OrderId(resultSet.getString("order_id")),
                        OrderStatus.valueOf(resultSet.getString("local_status")),
                        omsStatus(resultSet.getString("exchange_status")),
                        resultSet.getString("exchange_order_id"),
                        resultSet.getBigDecimal("filled_quantity"),
                        resultSet.getBigDecimal("average_fill_price"),
                        resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    @Override
    @Transactional
    public boolean apply(OmsReconciliationCandidate candidate, Instant reconciledAt) {
        if (candidate.filledQuantity().signum() > 0 && candidate.averageFillPrice() == null) {
            return false;
        }
        var terminal = candidate.exchangeStatus().terminal();
        var changed = jdbcClient.sql("""
                update oms_order
                set status = :targetStatus,
                    exchange_order_id = coalesce(exchange_order_id, :exchangeOrderId),
                    filled_quantity = :filledQuantity,
                    average_fill_price = :averageFillPrice,
                    submitted_at = coalesce(submitted_at, :exchangeOccurredAt),
                    terminal_at = case when :terminal then :exchangeOccurredAt else terminal_at end,
                    submission_claim_owner = null, submission_claim_until = null,
                    updated_at = :reconciledAt, version = version + 1
                where order_id = :orderId and status = :currentStatus
                """)
                .param("orderId", candidate.orderId().value())
                .param("currentStatus", candidate.currentStatus().name())
                .param("targetStatus", candidate.exchangeStatus().name())
                .param("exchangeOrderId", candidate.exchangeOrderId())
                .param("filledQuantity", candidate.filledQuantity())
                .param("averageFillPrice", candidate.averageFillPrice())
                .param("exchangeOccurredAt", timestamp(candidate.exchangeOccurredAt()))
                .param("terminal", terminal)
                .param("reconciledAt", timestamp(reconciledAt))
                .update();
        if (changed == 1) {
            appendEvent(candidate, reconciledAt);
        }
        return changed == 1;
    }

    @Override
    public void complete(String reconciliationId, int discrepancyCount, Instant completedAt) {
        jdbcClient.sql("""
                update exchange_reconciliation_run
                set status = :status, discrepancy_count = :discrepancyCount,
                    completed_at = :completedAt
                where reconciliation_id = :reconciliationId and status = 'RUNNING'
                """)
                .param("reconciliationId", reconciliationId)
                .param("status", discrepancyCount == 0 ? "COMPLETED" : "PARTIAL")
                .param("discrepancyCount", discrepancyCount)
                .param("completedAt", timestamp(completedAt))
                .update();
    }

    @Override
    public void fail(
            String reconciliationId,
            String errorCode,
            String safeMessage,
            Instant failedAt) {
        jdbcClient.sql("""
                update exchange_reconciliation_run
                set status = 'FAILED', error_code = :errorCode,
                    error_message = :errorMessage, completed_at = :failedAt
                where reconciliation_id = :reconciliationId and status = 'RUNNING'
                """)
                .param("reconciliationId", reconciliationId)
                .param("errorCode", safe(errorCode, 80))
                .param("errorMessage", safe(safeMessage, 2_000))
                .param("failedAt", timestamp(failedAt))
                .update();
    }

    private void appendEvent(OmsReconciliationCandidate candidate, Instant occurredAt) {
        var sequence = jdbcClient.sql("""
                select coalesce(max(sequence), 0) + 1
                from oms_order_event where order_id = :orderId
                """)
                .param("orderId", candidate.orderId().value())
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                insert into oms_order_event (
                  event_id, order_id, sequence, event_type, from_status,
                  to_status, payload, occurred_at
                ) values (
                  :eventId, :orderId, :sequence, 'OrderReconciled', :fromStatus,
                  :toStatus, '{}'::jsonb, :occurredAt
                )
                """)
                .param("eventId", "event_" + hash(candidate.orderId().value() + ':' + sequence).substring(0, 40))
                .param("orderId", candidate.orderId().value())
                .param("sequence", sequence)
                .param("fromStatus", candidate.currentStatus().name())
                .param("toStatus", candidate.exchangeStatus().name())
                .param("occurredAt", timestamp(occurredAt))
                .update();
    }

    private static OrderStatus omsStatus(String exchangeStatus) {
        return switch (exchangeStatus.toUpperCase(Locale.ROOT)) {
            case "NEW", "SUBMITTED" -> OrderStatus.SUBMITTED;
            case "PARTIALLY_FILLED" -> OrderStatus.PARTIALLY_FILLED;
            case "FILLED" -> OrderStatus.FILLED;
            case "CANCELLED" -> OrderStatus.CANCELLED;
            case "REJECTED" -> OrderStatus.REJECTED;
            case "EXPIRED" -> OrderStatus.EXPIRED;
            default -> OrderStatus.SUBMITTED;
        };
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String safe(String value, int maximumLength) {
        var normalized = Objects.requireNonNull(value, "value").strip();
        return normalized.substring(0, Math.min(normalized.length(), maximumLength));
    }
}
