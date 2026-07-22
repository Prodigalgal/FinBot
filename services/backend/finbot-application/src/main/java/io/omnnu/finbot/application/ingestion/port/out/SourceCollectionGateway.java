package io.omnnu.finbot.application.ingestion.port.out;

import io.omnnu.finbot.application.ingestion.dto.CollectedPayload;

import io.omnnu.finbot.domain.ingestion.InformationSource;
import java.util.List;

@FunctionalInterface
public interface SourceCollectionGateway {
    List<CollectedPayload> collect(InformationSource source, String query);
}
