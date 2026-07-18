package io.omnnu.finbot.application.ingestion;

@FunctionalInterface
public interface ContentEnvelopeBuilder {
    ContentEnvelope build(CollectedPayload payload);
}
