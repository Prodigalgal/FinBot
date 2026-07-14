package io.omnnu.finbot.infrastructure.ingestion;

import io.omnnu.finbot.application.ingestion.CollectedPayload;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import java.util.List;

interface SourceCollectorAdapter {
    boolean supports(SourceMode mode);

    List<CollectedPayload> collect(InformationSource source, String query);
}
