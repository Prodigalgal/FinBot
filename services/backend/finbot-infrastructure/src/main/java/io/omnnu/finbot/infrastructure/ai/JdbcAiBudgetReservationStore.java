package io.omnnu.finbot.infrastructure.ai;

import static io.omnnu.finbot.infrastructure.jdbc.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.ai.AiBudgetExceededException;
import io.omnnu.finbot.application.ai.AiBudgetReservationStore;
import io.omnnu.finbot.domain.ai.AiInvocationId;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAiBudgetReservationStore implements AiBudgetReservationStore {
    private final JdbcClient jdbcClient;

    public JdbcAiBudgetReservationStore(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional
    public void reserve(
            AiInvocationId invocationId,
            WorkflowRunId runId,
            AiProviderProfileId providerProfileId,
            String modelName,
            long estimatedInputTokens,
            long maximumOutputTokens,
            long maximumWorkflowTokens,
            BigDecimal maximumWorkflowCostUsd,
            Instant reservedAt) {
        var reservedTokens = Math.addExact(estimatedInputTokens, maximumOutputTokens);
        var reservedCost = jdbcClient.sql("""
                select (
                  :estimatedInputTokens * input_usd_per_million
                  + :maximumOutputTokens * output_usd_per_million
                ) / 1000000
                from ai_model_profile
                where provider_profile_id = :providerProfileId and model_name = :modelName
                """)
                .param("estimatedInputTokens", estimatedInputTokens)
                .param("maximumOutputTokens", maximumOutputTokens)
                .param("providerProfileId", providerProfileId.value())
                .param("modelName", modelName)
                .query(BigDecimal.class)
                .optional()
                .orElseThrow(() -> new AiBudgetExceededException("AI model rate is not configured"));
        var updated = jdbcClient.sql("""
                update workflow_run
                set reserved_tokens = reserved_tokens + :reservedTokens,
                    reserved_cost_usd = reserved_cost_usd + :reservedCost,
                    updated_at = :reservedAt
                where run_id = :runId
                  and total_input_tokens + total_output_tokens + reserved_tokens + :reservedTokens
                      <= :maximumWorkflowTokens
                  and total_cost_usd + reserved_cost_usd + :reservedCost
                      <= :maximumWorkflowCost
                """)
                .param("runId", runId.value())
                .param("reservedTokens", reservedTokens)
                .param("reservedCost", reservedCost)
                .param("maximumWorkflowTokens", maximumWorkflowTokens)
                .param("maximumWorkflowCost", maximumWorkflowCostUsd)
                .param("reservedAt", timestamp(reservedAt))
                .update();
        if (updated != 1) {
            throw new AiBudgetExceededException("Workflow AI token or cost budget would be exceeded");
        }
        jdbcClient.sql("""
                insert into ai_budget_reservation (
                  invocation_id, run_id, reserved_tokens, reserved_cost_usd,
                  status, reserved_at
                ) values (
                  :invocationId, :runId, :reservedTokens, :reservedCost,
                  'RESERVED', :reservedAt
                )
                """)
                .param("invocationId", invocationId.value())
                .param("runId", runId.value())
                .param("reservedTokens", reservedTokens)
                .param("reservedCost", reservedCost)
                .param("reservedAt", timestamp(reservedAt))
                .update();
    }

}
