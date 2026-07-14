package io.omnnu.finbot.domain.quant;

import java.time.Instant;
import java.util.Objects;

public sealed interface QuantResearchEvent permits
        ResearchAcceptedEvent,
        ResearchProgressEvent,
        ResearchArtifactEvent,
        ResearchCompletedEvent,
        ResearchFailedEvent {

    QuantResearchEventId eventId();

    ResearchRunId researchRunId();

    long sequence();

    Instant occurredAt();

    default String eventType() {
        return switch (this) {
            case ResearchAcceptedEvent ignored -> "research.accepted";
            case ResearchProgressEvent ignored -> "research.progress";
            case ResearchArtifactEvent ignored -> "research.artifact";
            case ResearchCompletedEvent ignored -> "research.completed";
            case ResearchFailedEvent ignored -> "research.failed";
        };
    }

    default boolean terminal() {
        return this instanceof ResearchCompletedEvent || this instanceof ResearchFailedEvent;
    }

    static void requireEnvelope(
            QuantResearchEventId eventId,
            ResearchRunId researchRunId,
            long sequence,
            Instant occurredAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(researchRunId, "researchRunId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
