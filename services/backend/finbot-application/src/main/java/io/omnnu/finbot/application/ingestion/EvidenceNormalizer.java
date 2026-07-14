package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.ingestion.EvidenceId;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import java.util.Optional;

@FunctionalInterface
public interface EvidenceNormalizer {
    Optional<NormalizedDocument> normalize(
            InformationSource source,
            EvidenceId evidenceId,
            CollectedPayload payload);
}
