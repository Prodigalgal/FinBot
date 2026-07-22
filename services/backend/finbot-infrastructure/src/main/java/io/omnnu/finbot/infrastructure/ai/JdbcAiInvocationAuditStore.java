package io.omnnu.finbot.infrastructure.ai;

import static io.omnnu.finbot.infrastructure.jdbc.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.ai.AiInvocationAuditStore;
import io.omnnu.finbot.application.ai.AiInvocationCompletion;
import io.omnnu.finbot.application.ai.AiInvocationFailure;
import io.omnnu.finbot.application.ai.AiInvocationStart;
import io.omnnu.finbot.domain.ai.AiInvocationId;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAiInvocationAuditStore implements AiInvocationAuditStore {
    private final JdbcClient jdbcClient;

    public JdbcAiInvocationAuditStore(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    public void start(AiInvocationStart start) {
        jdbcClient.sql("""
                insert into ai_invocation (
                  invocation_id, run_id, node_id, provider_profile_id, protocol,
                  model_name, reasoning_effort, prompt_version, request_hash,
                  status, started_at
                ) values (
                  :invocationId, :runId, :nodeId, :providerProfileId, :protocol,
                  :modelName, :reasoningEffort, :promptVersion, :requestHash,
                  'STARTED', :startedAt
                )
                """)
                .param("invocationId", start.invocationId().value())
                .param("runId", start.runId().value())
                .param("nodeId", start.nodeId().value())
                .param("providerProfileId", start.providerProfileId().value())
                .param("protocol", start.protocol().name())
                .param("modelName", start.modelName())
                .param("reasoningEffort", start.reasoningEffort().name())
                .param("promptVersion", start.promptVersion())
                .param("requestHash", start.requestHash())
                .param("startedAt", timestamp(start.startedAt()))
                .update();
    }

    @Override
    @Transactional
    public void appendChunk(
            AiInvocationId invocationId,
            long sequence,
            String content,
            Instant occurredAt) {
        var inserted = jdbcClient.sql("""
                insert into ai_stream_chunk (invocation_id, sequence, content, occurred_at)
                values (:invocationId, :sequence, :content, :occurredAt)
                on conflict (invocation_id, sequence) do nothing
                """)
                .param("invocationId", invocationId.value())
                .param("sequence", sequence)
                .param("content", content)
                .param("occurredAt", timestamp(occurredAt))
                .update();
        if (inserted == 1) {
            jdbcClient.sql("""
                    update ai_invocation set status = 'STREAMING'
                    where invocation_id = :invocationId and status = 'STARTED'
                    """)
                    .param("invocationId", invocationId.value())
                    .update();
        }
    }

    @Override
    @Transactional
    public void complete(AiInvocationCompletion completion) {
        var changed = jdbcClient.sql("""
                update ai_invocation invocation
                set status = 'COMPLETED',
                    input_tokens = :inputTokens,
                    output_tokens = :outputTokens,
                    estimated_cost_usd = coalesce((
                      select (
                        :inputTokens * model.input_usd_per_million
                        + :outputTokens * model.output_usd_per_million
                      ) / 1000000
                      from ai_model_profile model
                      where model.provider_profile_id = invocation.provider_profile_id
                        and model.model_name = invocation.model_name
                    ), 0),
                    latency_milliseconds = greatest(
                      0, extract(epoch from (:completedAt - invocation.started_at)) * 1000
                    )::bigint,
                    finish_reason = :finishReason,
                    completed_at = :completedAt
                where invocation_id = :invocationId
                  and status in ('STARTED', 'STREAMING')
                """)
                .param("invocationId", completion.invocationId().value())
                .param("inputTokens", completion.inputTokens())
                .param("outputTokens", completion.outputTokens())
                .param("finishReason", safe(completion.finishReason(), 80))
                .param("completedAt", timestamp(completion.completedAt()))
                .update();
        if (changed == 1) {
            jdbcClient.sql("""
                    update workflow_run run
                    set total_input_tokens = total_input_tokens + invocation.input_tokens,
                        total_output_tokens = total_output_tokens + invocation.output_tokens,
                        total_cost_usd = total_cost_usd + invocation.estimated_cost_usd,
                        updated_at = :completedAt
                    from ai_invocation invocation
                    where invocation.invocation_id = :invocationId
                      and run.run_id = invocation.run_id
                    """)
                    .param("invocationId", completion.invocationId().value())
                    .param("completedAt", timestamp(completion.completedAt()))
                    .update();
        }
        JdbcAiBudgetReservationReleaser.release(
                jdbcClient,
                completion.invocationId().value(),
                completion.completedAt());
    }

    @Override
    @Transactional
    public void fail(AiInvocationFailure failure) {
        jdbcClient.sql("""
                update ai_invocation
                set status = 'FAILED',
                    latency_milliseconds = greatest(
                      0, extract(epoch from (:failedAt - started_at)) * 1000
                    )::bigint,
                    error_code = :errorCode,
                    error_message = :errorMessage,
                    completed_at = :failedAt
                where invocation_id = :invocationId
                  and status in ('STARTED', 'STREAMING')
                """)
                .param("invocationId", failure.invocationId().value())
                .param("errorCode", safe(failure.errorCode(), 80))
                .param("errorMessage", safe(failure.safeMessage(), 2000))
                .param("failedAt", timestamp(failure.failedAt()))
                .update();
        JdbcAiBudgetReservationReleaser.release(
                jdbcClient,
                failure.invocationId().value(),
                failure.failedAt());
    }

    private static String safe(String value, int maximumLength) {
        var normalized = Objects.requireNonNullElse(value, "unknown").strip();
        return normalized.substring(0, Math.min(normalized.length(), maximumLength));
    }
}
