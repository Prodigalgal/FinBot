package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.ingestion.AiWebSearchBinding;
import io.omnnu.finbot.domain.ingestion.SourceId;

public interface AiWebSearchGateway {
    AiWebSearchResult search(
            SourceId sourceId,
            AiWebSearchBinding binding,
            String query,
            int maximumResults);
}
