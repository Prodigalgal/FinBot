package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.research.ResearchArtifactId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;
import java.util.List;

public record ResearchEvidencePackage(
        ResearchArtifactId artifactId,
        WorkflowRunId workflowRunId,
        int schemaVersion,
        String requestSummary,
        List<SourceCollectionSummary> collections,
        List<EvidenceExcerpt> evidence,
        Instant createdAt) {
    public ResearchEvidencePackage {
        collections = List.copyOf(collections);
        evidence = List.copyOf(evidence);
    }
}
