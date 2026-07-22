package io.omnnu.finbot.application.research.dto;

import io.omnnu.finbot.domain.research.ResearchArtifactId;

public record CompressionBatchResult(
        ResearchArtifactId artifactId,
        int completedCount,
        int failedCount,
        int skippedCount) {
}
