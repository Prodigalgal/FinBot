package io.omnnu.finbot.application.quant.dto;

import io.omnnu.finbot.domain.quant.QuantMetric;
import io.omnnu.finbot.domain.quant.ResearchRunId;
import io.omnnu.finbot.domain.research.ResearchArtifactId;
import java.util.List;

public record QuantResearchExecutionResult(
        ResearchRunId researchRunId,
        QuantResearchExecutionStatus status,
        ResearchArtifactId resultArtifactId,
        List<QuantMetric> metrics,
        long observationCount,
        String errorCode,
        String safeMessage) {
    public QuantResearchExecutionResult {
        metrics = List.copyOf(metrics);
    }
}
