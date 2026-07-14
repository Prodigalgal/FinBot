package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.ingestion.InformationSource;
import java.util.List;

@FunctionalInterface
public interface SourceCollectionGateway {
    List<CollectedPayload> collect(InformationSource source, String query);
}
