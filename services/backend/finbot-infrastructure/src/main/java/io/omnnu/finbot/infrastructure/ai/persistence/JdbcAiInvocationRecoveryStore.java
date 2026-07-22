package io.omnnu.finbot.infrastructure.ai.persistence;

import static io.omnnu.finbot.infrastructure.jdbc.persistence.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.ai.port.out.AiInvocationRecoveryStore;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcAiInvocationRecoveryStore implements AiInvocationRecoveryStore {
    private static final String RECOVERY_CODE = "WORKER_RESTART_RECOVERY";
    private static final String RECOVERY_MESSAGE =
            "AI invocation was orphaned by a worker restart and closed during startup recovery";

    private final JdbcClient jdbcClient;

    public JdbcAiInvocationRecoveryStore(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional
    public int failOrphanedInvocations(Instant recoveredAt) {
        Objects.requireNonNull(recoveredAt, "recoveredAt");
        List<String> invocationIds = jdbcClient.sql("""
                select invocation_id
                from ai_invocation
                where status in ('STARTED', 'STREAMING')
                order by id
                for update
                """)
                .query(String.class)
                .list();
        var recovered = 0;
        for (var invocationId : invocationIds) {
            recovered += jdbcClient.sql("""
                    update ai_invocation
                    set status = 'FAILED',
                        latency_milliseconds = greatest(
                          0, extract(epoch from (:recoveredAt - started_at)) * 1000
                        )::bigint,
                        error_code = :errorCode,
                        error_message = :errorMessage,
                        completed_at = :recoveredAt
                    where invocation_id = :invocationId
                      and status in ('STARTED', 'STREAMING')
                    """)
                    .param("invocationId", invocationId)
                    .param("recoveredAt", timestamp(recoveredAt))
                    .param("errorCode", RECOVERY_CODE)
                    .param("errorMessage", RECOVERY_MESSAGE)
                    .update();
            JdbcAiBudgetReservationReleaser.release(jdbcClient, invocationId, recoveredAt);
        }
        return recovered;
    }
}
