package io.omnnu.finbot.infrastructure.ai;

import static io.omnnu.finbot.infrastructure.jdbc.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.ai.AiInvocationRecoveryStore;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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
    public int failOrphanedInvocations(Instant recoveredAt) {
        Objects.requireNonNull(recoveredAt, "recoveredAt");
        return jdbcClient.sql("""
                update ai_invocation
                set status = 'FAILED',
                    latency_milliseconds = greatest(
                      0, extract(epoch from (:recoveredAt - started_at)) * 1000
                    )::bigint,
                    error_code = :errorCode,
                    error_message = :errorMessage,
                    completed_at = :recoveredAt
                where status in ('STARTED', 'STREAMING')
                """)
                .param("recoveredAt", timestamp(recoveredAt))
                .param("errorCode", RECOVERY_CODE)
                .param("errorMessage", RECOVERY_MESSAGE)
                .update();
    }
}
