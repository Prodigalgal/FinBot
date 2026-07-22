package io.omnnu.finbot.application.trading.dto;

import io.omnnu.finbot.domain.ai.AiInvocationId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;

public record StoredExecutionAiReview(
        String reviewId,
        String automationRunId,
        WorkflowRunId workflowRunId,
        TradeExecutionAiStage stage,
        AiInvocationId invocationId,
        String canonicalJson,
        String outputHash,
        Instant createdAt) {
}
