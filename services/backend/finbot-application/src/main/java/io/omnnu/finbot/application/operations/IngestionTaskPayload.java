package io.omnnu.finbot.application.operations;

import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.Objects;

public record IngestionTaskPayload(
        WorkflowRunId workflowRunId,
        SourceId sourceId,
        String query) implements BackgroundTaskPayload {
    public IngestionTaskPayload {
        Objects.requireNonNull(sourceId, "sourceId");
        query = Objects.requireNonNull(query, "query").strip();
        if (query.isEmpty() || query.length() > 1000) {
            throw new IllegalArgumentException("Ingestion task payload is invalid");
        }
    }
}
