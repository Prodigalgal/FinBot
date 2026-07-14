package io.omnnu.finbot.domain.quant;

import io.omnnu.finbot.domain.shared.DomainText;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ResearchCompletedEvent(
        QuantResearchEventId eventId,
        ResearchRunId researchRunId,
        long sequence,
        Instant occurredAt,
        List<QuantMetric> metrics,
        List<ResearchArtifact> artifacts,
        long observationCount,
        String resultFingerprint) implements QuantResearchEvent {

    public ResearchCompletedEvent {
        QuantResearchEvent.requireEnvelope(eventId, researchRunId, sequence, occurredAt);
        metrics = List.copyOf(Objects.requireNonNull(metrics, "metrics"));
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        if (observationCount < 0) {
            throw new IllegalArgumentException("observationCount must not be negative");
        }
        resultFingerprint = DomainText.required(resultFingerprint, "resultFingerprint", 200);
    }
}
