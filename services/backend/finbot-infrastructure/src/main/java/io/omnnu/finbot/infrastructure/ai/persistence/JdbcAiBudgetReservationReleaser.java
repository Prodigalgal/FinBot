package io.omnnu.finbot.infrastructure.ai.persistence;

import static io.omnnu.finbot.infrastructure.jdbc.persistence.PostgresJdbcParameters.timestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

final class JdbcAiBudgetReservationReleaser {
    private JdbcAiBudgetReservationReleaser() {
    }

    static boolean release(JdbcClient jdbcClient, String invocationId, Instant releasedAt) {
        Objects.requireNonNull(jdbcClient, "jdbcClient");
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(releasedAt, "releasedAt");
        var reservation = jdbcClient.sql("""
                select run_id, reserved_tokens, reserved_cost_usd
                from ai_budget_reservation
                where invocation_id = :invocationId and status = 'RESERVED'
                for update
                """)
                .param("invocationId", invocationId)
                .query((resultSet, rowNumber) -> new Reservation(
                        resultSet.getString("run_id"),
                        resultSet.getLong("reserved_tokens"),
                        resultSet.getBigDecimal("reserved_cost_usd")))
                .optional();
        if (reservation.isEmpty()) {
            return false;
        }
        var value = reservation.orElseThrow();
        var workflowUpdated = jdbcClient.sql("""
                update workflow_run
                set reserved_tokens = reserved_tokens - :reservedTokens,
                    reserved_cost_usd = reserved_cost_usd - :reservedCost,
                    updated_at = :releasedAt
                where run_id = :runId
                  and reserved_tokens >= :reservedTokens
                  and reserved_cost_usd >= :reservedCost
                """)
                .param("runId", value.runId())
                .param("reservedTokens", value.reservedTokens())
                .param("reservedCost", value.reservedCost())
                .param("releasedAt", timestamp(releasedAt))
                .update();
        if (workflowUpdated != 1) {
            throw new IllegalStateException(
                    "Workflow AI reservation totals are inconsistent for invocation " + invocationId);
        }
        var reservationUpdated = jdbcClient.sql("""
                update ai_budget_reservation
                set status = 'RELEASED', released_at = :releasedAt
                where invocation_id = :invocationId and status = 'RESERVED'
                """)
                .param("invocationId", invocationId)
                .param("releasedAt", timestamp(releasedAt))
                .update();
        if (reservationUpdated != 1) {
            throw new IllegalStateException("AI reservation changed during release for invocation " + invocationId);
        }
        return true;
    }

    private record Reservation(String runId, long reservedTokens, BigDecimal reservedCost) {
    }
}
