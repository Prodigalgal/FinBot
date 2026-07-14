package io.omnnu.finbot.application.market;

import io.omnnu.finbot.domain.research.ResearchArtifactId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;

public record MarketDataArtifactRecord(
        ResearchArtifactId artifactId,
        WorkflowRunId workflowRunId,
        int schemaVersion,
        EncodedMarketDataArtifact encoded,
        Instant createdAt) {
}
