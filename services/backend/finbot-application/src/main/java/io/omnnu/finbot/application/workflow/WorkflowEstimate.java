package io.omnnu.finbot.application.workflow;

import java.math.BigDecimal;
import java.util.List;

public record WorkflowEstimate(
        String versionId,
        int debateRounds,
        long estimatedCalls,
        long estimatedInputTokens,
        long maximumOutputTokens,
        BigDecimal primaryCostUsd,
        BigDecimal fallbackWorstCaseCostUsd,
        BigDecimal configuredCostLimitUsd,
        long configuredTokenLimit,
        List<NodeEstimate> nodes,
        List<String> warnings) {
    public WorkflowEstimate {
        nodes = List.copyOf(nodes);
        warnings = List.copyOf(warnings);
    }

    public record NodeEstimate(
            String nodeId,
            String displayName,
            long estimatedCalls,
            long estimatedInputTokens,
            long maximumOutputTokens,
            String primaryProvider,
            String primaryModel,
            BigDecimal primaryCostUsd,
            String fallbackProvider,
            String fallbackModel,
            BigDecimal fallbackCostUsd,
            boolean rateComplete) {
    }
}
