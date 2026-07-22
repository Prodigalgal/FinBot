package io.omnnu.finbot.application.workspace.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record WorkflowLearning(
        String definitionId,
        String versionId,
        long runCount,
        long completedRunCount,
        long failedRunCount,
        BigDecimal totalCostUsd,
        List<NodePerformance> nodes,
        List<Failure> recentFailures,
        Instant generatedAt) {
    public WorkflowLearning {
        nodes = List.copyOf(nodes);
        recentFailures = List.copyOf(recentFailures);
    }

    public record NodePerformance(
            String nodeId,
            String displayName,
            long invocationCount,
            long successfulInvocationCount,
            long failedInvocationCount,
            long inputTokens,
            long outputTokens,
            BigDecimal costUsd,
            Long averageLatencyMilliseconds) {
    }

    public record Failure(
            String runId,
            String nodeId,
            String errorCode,
            String errorMessage,
            Instant occurredAt) {
    }
}
