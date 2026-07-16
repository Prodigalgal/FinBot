package io.omnnu.finbot.api.ingestion;

import io.omnnu.finbot.application.ingestion.SourceCollectionSummary;

public record SourceTestResponse(
        String collectionId,
        String sourceId,
        String status,
        int fetchedCount,
        int insertedCount,
        int duplicateCount,
        String errorCode,
        String errorMessage) {
    static SourceTestResponse from(SourceCollectionSummary summary) {
        return new SourceTestResponse(
                summary.collectionId().value(),
                summary.sourceId().value(),
                summary.status().name(),
                summary.fetchedCount(),
                summary.insertedCount(),
                summary.duplicateCount(),
                summary.errorCode(),
                summary.safeMessage());
    }
}
