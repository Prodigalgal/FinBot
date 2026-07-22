package io.omnnu.finbot.application.market.dto;

import io.omnnu.finbot.domain.research.ResearchArtifactId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.research.ResearchDataPlane;
import java.time.Instant;

public record MarketDataArtifactRecord(
        ResearchArtifactId artifactId,
        WorkflowRunId workflowRunId,
        ResearchDataPlane dataPlane,
        int schemaVersion,
        EncodedMarketDataArtifact encoded,
        Instant createdAt) {
}
