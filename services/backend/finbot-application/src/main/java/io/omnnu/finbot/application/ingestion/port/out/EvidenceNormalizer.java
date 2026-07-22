package io.omnnu.finbot.application.ingestion.port.out;

import io.omnnu.finbot.application.ingestion.dto.CollectedPayload;
import io.omnnu.finbot.application.ingestion.dto.NormalizedDocument;

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
