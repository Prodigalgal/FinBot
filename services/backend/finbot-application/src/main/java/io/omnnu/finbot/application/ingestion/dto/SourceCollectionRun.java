package io.omnnu.finbot.application.ingestion.dto;

import io.omnnu.finbot.domain.ingestion.CollectionRunId;
import io.omnnu.finbot.domain.ingestion.CollectionStatus;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;

public record SourceCollectionRun(
        CollectionRunId collectionId,
        WorkflowRunId workflowRunId,
        SourceId sourceId,
        String query,
        CollectionStatus status,
        int fetchedCount,
        int insertedCount,
        int duplicateCount,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant completedAt) {
}
