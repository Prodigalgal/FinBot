package io.omnnu.finbot.application.review;

import io.omnnu.finbot.application.research.ResearchHistoryDetail;
import java.math.BigDecimal;
import java.util.List;

public record ResearchComparison(
        ResearchHistoryDetail.Summary left,
        ResearchHistoryDetail.Summary right,
        long inputTokenDelta,
        long outputTokenDelta,
        BigDecimal costDeltaUsd,
        Long durationDeltaSeconds,
        String leftConclusion,
        String rightConclusion,
        List<NodeComparison> nodes) {
    public ResearchComparison {
        nodes = List.copyOf(nodes);
    }

    public record NodeComparison(
            String nodeId,
            int round,
            String leftStatus,
            String rightStatus,
            String leftSummary,
            String rightSummary,
            boolean changed) {
    }
}
