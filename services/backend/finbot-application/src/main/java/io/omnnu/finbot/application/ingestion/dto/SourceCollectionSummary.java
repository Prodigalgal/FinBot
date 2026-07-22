package io.omnnu.finbot.application.ingestion.dto;

import io.omnnu.finbot.domain.ingestion.CollectionRunId;
import io.omnnu.finbot.domain.ingestion.CollectionStatus;
import io.omnnu.finbot.domain.ingestion.SourceId;

public record SourceCollectionSummary(
        CollectionRunId collectionId,
        SourceId sourceId,
        CollectionStatus status,
        int fetchedCount,
        int insertedCount,
        int duplicateCount,
        String errorCode,
        String safeMessage) {
}
