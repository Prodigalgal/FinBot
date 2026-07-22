package io.omnnu.finbot.application.ingestion.port.out;

import io.omnnu.finbot.application.ingestion.dto.CollectedPayload;
import io.omnnu.finbot.application.ingestion.dto.ContentEnvelope;

@FunctionalInterface
public interface ContentEnvelopeBuilder {
    ContentEnvelope build(CollectedPayload payload);
}
