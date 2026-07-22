package io.omnnu.finbot.application.ingestion.port.out;

import io.omnnu.finbot.application.ingestion.dto.AiWebSearchResult;

import io.omnnu.finbot.domain.ingestion.AiWebSearchBinding;
import io.omnnu.finbot.domain.ingestion.SourceId;

public interface AiWebSearchGateway {
    AiWebSearchResult search(
            SourceId sourceId,
            AiWebSearchBinding binding,
            String query,
            int maximumResults);
}
