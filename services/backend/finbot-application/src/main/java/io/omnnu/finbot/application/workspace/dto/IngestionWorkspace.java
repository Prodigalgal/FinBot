package io.omnnu.finbot.application.workspace.dto;

import java.time.Instant;
import java.util.List;

public record IngestionWorkspace(
        long rawEvidenceCount,
        long normalizedDocumentCount,
        long compressionCount,
        long aiReviewCount,
        List<SourceStatus> sources,
        List<CollectionRun> recentRuns,
        List<EvidenceAiReviewSummary> recentAiReviews,
        String sourceCatalogVersion,
        String sourceCatalogManifestHash,
        int sourceCatalogSize,
        Instant generatedAt) {
    public IngestionWorkspace {
        sources = List.copyOf(sources);
        recentRuns = List.copyOf(recentRuns);
        recentAiReviews = List.copyOf(recentAiReviews);
        sourceCatalogVersion = java.util.Objects.requireNonNull(sourceCatalogVersion, "sourceCatalogVersion");
        sourceCatalogManifestHash = java.util.Objects.requireNonNull(
                sourceCatalogManifestHash, "sourceCatalogManifestHash");
        if (sourceCatalogSize < 1) {
            throw new IllegalArgumentException("sourceCatalogSize must be positive");
        }
    }

    public record SourceStatus(
            String sourceId,
            String displayName,
            String mode,
            String tier,
            String category,
            String outboundRoute,
            boolean credentialSupported,
            boolean credentialConfigured,
            String credentialSource,
            String credentialFingerprint,
            long credentialVersion,
            boolean enabled,
            long version,
            String latestStatus,
            int fetchedCount,
            int insertedCount,
            int duplicateCount,
            String errorCode,
            String errorMessage,
            Instant lastCollectedAt) {
    }

    public record CollectionRun(
            String collectionId,
            String workflowRunId,
            String sourceId,
            String sourceName,
            String query,
            String status,
            int fetchedCount,
            int insertedCount,
            int duplicateCount,
            String errorCode,
            String errorMessage,
            Instant startedAt,
            Instant completedAt) {
    }

    public record EvidenceAiReviewSummary(
            String reviewId,
            String workflowRunId,
            String documentId,
            String nodeId,
            String stage,
            String status,
            String summary,
            List<String> citations,
            String errorCode,
            String errorMessage,
            Instant createdAt) {
        public EvidenceAiReviewSummary {
            citations = List.copyOf(citations);
        }
    }
}
