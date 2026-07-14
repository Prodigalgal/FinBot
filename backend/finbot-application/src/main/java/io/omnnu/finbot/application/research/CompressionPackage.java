package io.omnnu.finbot.application.research;

import io.omnnu.finbot.domain.research.ResearchArtifactId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;
import java.util.List;

public record CompressionPackage(
        ResearchArtifactId artifactId,
        WorkflowRunId workflowRunId,
        int schemaVersion,
        String policy,
        List<CompressionItem> items,
        Instant createdAt) {
    public CompressionPackage {
        items = List.copyOf(items);
    }
}
