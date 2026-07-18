package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.ingestion.CollectionRunId;
import io.omnnu.finbot.domain.ingestion.CollectionStatus;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IngestionRepository {
    List<InformationSource> listSources(boolean enabledOnly);

    Optional<InformationSource> findSource(SourceId sourceId);

    Optional<InformationSource> createSource(InformationSource source, Instant createdAt);

    Optional<InformationSource> updateSource(
            InformationSource source,
            long expectedVersion,
            Instant updatedAt);

    boolean archiveSource(SourceId sourceId, long expectedVersion, Instant archivedAt);

    Optional<InformationSource> setSourceEnabled(
            SourceId sourceId,
            boolean enabled,
            long expectedVersion,
            Instant updatedAt);

    void startCollection(SourceCollectionRun collectionRun);

    void recordFetchAttempt(SourceFetchAttempt attempt);

    PersistEvidenceResult saveEvidence(
            RawEvidenceRecord evidence,
            Optional<NormalizedDocument> normalizedDocument);

    void finishCollection(
            CollectionRunId collectionId,
            CollectionStatus status,
            int fetchedCount,
            int insertedCount,
            int duplicateCount,
            String errorCode,
            String errorMessage,
            Instant completedAt);

    void saveEvidencePackage(ResearchEvidencePackage evidencePackage, String contentHash);

    List<NormalizedDocument> listRecentDocuments(SourceId sourceId, int limit);
}
