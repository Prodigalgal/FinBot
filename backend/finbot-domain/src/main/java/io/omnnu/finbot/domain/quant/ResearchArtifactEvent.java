package io.omnnu.finbot.domain.quant;

import java.time.Instant;
import java.util.Objects;

public record ResearchArtifactEvent(
        QuantResearchEventId eventId,
        ResearchRunId researchRunId,
        long sequence,
        Instant occurredAt,
        ResearchArtifact artifact) implements QuantResearchEvent {

    public ResearchArtifactEvent {
        QuantResearchEvent.requireEnvelope(eventId, researchRunId, sequence, occurredAt);
        Objects.requireNonNull(artifact, "artifact");
    }
}
