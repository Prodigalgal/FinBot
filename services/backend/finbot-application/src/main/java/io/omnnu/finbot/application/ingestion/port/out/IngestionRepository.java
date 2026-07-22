package io.omnnu.finbot.application.ingestion.port.out;

import io.omnnu.finbot.application.ingestion.dto.NormalizedDocument;
import io.omnnu.finbot.application.ingestion.dto.PersistEvidenceResult;
import io.omnnu.finbot.application.ingestion.dto.RawEvidenceRecord;
import io.omnnu.finbot.application.ingestion.dto.ResearchEvidencePackage;
import io.omnnu.finbot.application.ingestion.dto.SourceAttemptHistory;
import io.omnnu.finbot.application.ingestion.dto.SourceCollectionRun;
import io.omnnu.finbot.application.ingestion.dto.SourceFetchAttempt;

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

    default SourceAttemptHistory sourceAttemptHistory(SourceId sourceId) {
        return new SourceAttemptHistory(null, null, null, null, null, null, null);
    }

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

    default int recoverStaleCollections(Instant staleBefore, Instant recoveredAt) {
        return 0;
    }

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
