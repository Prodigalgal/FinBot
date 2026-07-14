package io.omnnu.finbot.infrastructure.exchange;

import static io.omnnu.finbot.infrastructure.jdbc.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.exchange.ExchangeSubmissionResult;
import io.omnnu.finbot.application.exchange.ExchangeSubmissionStatus;
import io.omnnu.finbot.application.exchange.ExecutableOrder;
import io.omnnu.finbot.application.exchange.OmsExecutionStore;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.oms.OrderId;
import io.omnnu.finbot.domain.oms.OrderStateMachine;
import io.omnnu.finbot.domain.oms.OrderStatus;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcOmsExecutionStore implements OmsExecutionStore {
    private final JdbcClient jdbcClient;

    public JdbcOmsExecutionStore(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional
    public Optional<ExecutableOrder> claim(
            OrderId orderId,
            String workerId,
            Instant claimedAt,
            Duration leaseDuration) {
        var row = jdbcClient.sql("""
                select status, submission_claim_until
                from oms_order where order_id = :orderId for update
                """)
                .param("orderId", orderId.value())
                .query((resultSet, rowNumber) -> new ClaimState(
                        OrderStatus.valueOf(resultSet.getString("status")),
                        nullableInstant(resultSet.getObject(
                                "submission_claim_until",
                                OffsetDateTime.class))))
                .optional();
        if (row.isEmpty() || !claimable(row.orElseThrow(), claimedAt)) {
            return Optional.empty();
        }
        var previousStatus = row.orElseThrow().status();
        if (previousStatus == OrderStatus.PLANNED) {
            OrderStateMachine.requireTransition(previousStatus, OrderStatus.SUBMITTING);
        }
        var claimedUntil = claimedAt.plus(leaseDuration);
        jdbcClient.sql("""
                update oms_order
                set status = 'SUBMITTING', submission_claim_owner = :workerId,
                    submission_claim_until = :claimedUntil, updated_at = :claimedAt,
                    version = version + 1
                where order_id = :orderId
                """)
                .param("orderId", orderId.value())
                .param("workerId", workerId)
                .param("claimedUntil", timestamp(claimedUntil))
                .param("claimedAt", timestamp(claimedAt))
                .update();
        var attemptNumber = jdbcClient.sql("""
                select coalesce(max(attempt_number), 0) + 1
                from exchange_submission_attempt where order_id = :orderId
                """)
                .param("orderId", orderId.value())
                .query(Integer.class)
                .single();
        var order = load(orderId, attemptNumber, claimedUntil);
        jdbcClient.sql("""
                insert into exchange_submission_attempt (
                  attempt_id, order_id, attempt_number, request_hash, status, started_at
                ) values (
                  :attemptId, :orderId, :attemptNumber, :requestHash, 'STARTED', :startedAt
                )
                """)
                .param("attemptId", attemptId(orderId, attemptNumber))
                .param("orderId", orderId.value())
                .param("attemptNumber", attemptNumber)
                .param("requestHash", requestHash(order))
                .param("startedAt", timestamp(claimedAt))
                .update();
        if (previousStatus == OrderStatus.PLANNED) {
            appendOrderEvent(orderId, previousStatus, OrderStatus.SUBMITTING, claimedAt);
        }
        return Optional.of(order);
    }

    @Override
    @Transactional
    public void recordResult(
            ExecutableOrder order,
            ExchangeSubmissionResult result,
            Instant completedAt) {
        var responsePayload = result.responseJson();
        jdbcClient.sql("""
                update exchange_submission_attempt
                set status = :status, exchange_order_id = :exchangeOrderId,
                    http_status = :httpStatus,
                    response_payload = case when :responsePayload is null
                        then null else cast(:responsePayload as jsonb) end,
                    error_code = :errorCode, error_message = :errorMessage,
                    completed_at = :completedAt
                where order_id = :orderId and attempt_number = :attemptNumber
                  and status = 'STARTED'
                """)
                .param("orderId", order.orderId().value())
                .param("attemptNumber", order.attemptNumber())
                .param("status", result.status().name())
                .param("exchangeOrderId", result.exchangeOrderId())
                .param("httpStatus", result.httpStatus())
                .param("responsePayload", responsePayload)
                .param("errorCode", safe(result.errorCode(), 80))
                .param("errorMessage", safe(result.safeMessage(), 2_000))
                .param("completedAt", timestamp(completedAt))
                .update();
        switch (result.status()) {
            case ACKNOWLEDGED -> transition(
                    order,
                    OrderStatus.SUBMITTED,
                    result.exchangeOrderId(),
                    completedAt);
            case REJECTED -> transition(
                    order,
                    OrderStatus.REJECTED,
                    result.exchangeOrderId(),
                    completedAt);
            case UNKNOWN -> jdbcClient.sql("""
                    update oms_order
                    set submission_claim_until = :completedAt, updated_at = :completedAt,
                        version = version + 1
                    where order_id = :orderId and status = 'SUBMITTING'
                    """)
                    .param("orderId", order.orderId().value())
                    .param("completedAt", timestamp(completedAt))
                    .update();
        }
    }

    private ExecutableOrder load(OrderId orderId, int attemptNumber, Instant claimedUntil) {
        return jdbcClient.sql("""
                select exchange, environment, account_ref, symbol, side,
                       requested_quantity, leverage, client_order_id
                from oms_order where order_id = :orderId
                """)
                .param("orderId", orderId.value())
                .query((resultSet, rowNumber) -> new ExecutableOrder(
                        orderId,
                        attemptNumber,
                        ExchangeVenue.valueOf(resultSet.getString("exchange")),
                        ExchangeEnvironment.valueOf(resultSet.getString("environment")),
                        new ExchangeAccountId(resultSet.getString("account_ref")),
                        new InstrumentSymbol(resultSet.getString("symbol")),
                        DirectionalAction.valueOf(resultSet.getString("side")),
                        resultSet.getBigDecimal("requested_quantity"),
                        resultSet.getBigDecimal("leverage"),
                        resultSet.getString("client_order_id"),
                        claimedUntil))
                .single();
    }

    private void transition(
            ExecutableOrder order,
            OrderStatus target,
            String exchangeOrderId,
            Instant occurredAt) {
        OrderStateMachine.requireTransition(OrderStatus.SUBMITTING, target);
        var changed = jdbcClient.sql("""
                update oms_order
                set status = :status, exchange_order_id = :exchangeOrderId,
                    submitted_at = case when :status = 'SUBMITTED' then :occurredAt else submitted_at end,
                    terminal_at = case when :status = 'REJECTED' then :occurredAt else terminal_at end,
                    submission_claim_owner = null, submission_claim_until = null,
                    updated_at = :occurredAt, version = version + 1
                where order_id = :orderId and status = 'SUBMITTING'
                """)
                .param("orderId", order.orderId().value())
                .param("status", target.name())
                .param("exchangeOrderId", exchangeOrderId)
                .param("occurredAt", timestamp(occurredAt))
                .update();
        if (changed == 1) {
            appendOrderEvent(order.orderId(), OrderStatus.SUBMITTING, target, occurredAt);
        }
    }

    private void appendOrderEvent(
            OrderId orderId,
            OrderStatus from,
            OrderStatus to,
            Instant occurredAt) {
        var sequence = jdbcClient.sql("""
                select coalesce(max(sequence), 0) + 1
                from oms_order_event where order_id = :orderId
                """)
                .param("orderId", orderId.value())
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                insert into oms_order_event (
                  event_id, order_id, sequence, event_type, from_status,
                  to_status, payload, occurred_at
                ) values (
                  :eventId, :orderId, :sequence, :eventType, :fromStatus,
                  :toStatus, '{}'::jsonb, :occurredAt
                )
                """)
                .param("eventId", eventId(orderId, sequence))
                .param("orderId", orderId.value())
                .param("sequence", sequence)
                .param("eventType", "Order" + title(to.name()))
                .param("fromStatus", from.name())
                .param("toStatus", to.name())
                .param("occurredAt", timestamp(occurredAt))
                .update();
    }

    private static boolean claimable(ClaimState state, Instant now) {
        return state.status() == OrderStatus.PLANNED
                || (state.status() == OrderStatus.SUBMITTING
                        && state.claimedUntil() != null
                        && !state.claimedUntil().isAfter(now));
    }

    private static String requestHash(ExecutableOrder order) {
        return hash(order.exchange().name() + '|' + order.environment().name() + '|'
                + order.accountId().value() + '|' + order.symbol().value() + '|'
                + order.side().name() + '|' + order.quantity().toPlainString() + '|'
                + order.leverage().toPlainString() + '|' + order.clientOrderId());
    }

    private static String attemptId(OrderId orderId, int attemptNumber) {
        return "attempt_" + hash(orderId.value() + ':' + attemptNumber).substring(0, 40);
    }

    private static String eventId(OrderId orderId, long sequence) {
        return "event_" + hash(orderId.value() + ':' + sequence).substring(0, 40);
    }

    private static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String title(String value) {
        return value.charAt(0) + value.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String safe(String value, int maximumLength) {
        if (value == null) {
            return null;
        }
        var normalized = value.strip();
        return normalized.substring(0, Math.min(normalized.length(), maximumLength));
    }

    private static Instant nullableInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record ClaimState(OrderStatus status, Instant claimedUntil) {
    }
}
