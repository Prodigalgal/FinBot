package io.omnnu.finbot.application.ai;

import io.omnnu.finbot.domain.ai.AiInvocationId;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.math.BigDecimal;
import java.time.Instant;

public interface AiBudgetReservationStore {
    void reserve(
            AiInvocationId invocationId,
            WorkflowRunId runId,
            AiProviderProfileId providerProfileId,
            String modelName,
            long estimatedInputTokens,
            long maximumOutputTokens,
            long maximumWorkflowTokens,
            BigDecimal maximumWorkflowCostUsd,
            Instant reservedAt);

    void release(AiInvocationId invocationId, Instant releasedAt);
}
