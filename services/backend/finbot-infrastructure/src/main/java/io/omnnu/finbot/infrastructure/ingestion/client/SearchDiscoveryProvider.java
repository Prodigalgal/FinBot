package io.omnnu.finbot.infrastructure.ingestion.client;

import io.omnnu.finbot.application.ingestion.dto.CollectedPayload;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import java.util.List;

/** Provider-neutral boundary for search discovery channels. */
public interface SearchDiscoveryProvider {
    boolean supports(String provider);

    List<CollectedPayload> search(InformationSource source, String query);
}
