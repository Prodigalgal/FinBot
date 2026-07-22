package io.omnnu.finbot.application.ingestion.dto;

import io.omnnu.finbot.domain.research.ResearchArtifactId;
import java.util.List;

public record IngestionBatchResult(
        ResearchArtifactId artifactId,
        List<SourceCollectionSummary> collections,
        int fetchedCount,
        int insertedCount,
        int duplicateCount) {
    public IngestionBatchResult {
        collections = List.copyOf(collections);
    }
}
