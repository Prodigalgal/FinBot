package io.omnnu.finbot.domain.quant;

import io.omnnu.finbot.domain.shared.DomainText;
import java.time.Instant;
import java.util.Objects;

public record ResearchProgressEvent(
        QuantResearchEventId eventId,
        ResearchRunId researchRunId,
        long sequence,
        Instant occurredAt,
        ResearchStage stage,
        int progressBasisPoints,
        String safeSummary) implements QuantResearchEvent {

    public ResearchProgressEvent {
        QuantResearchEvent.requireEnvelope(eventId, researchRunId, sequence, occurredAt);
        Objects.requireNonNull(stage, "stage");
        if (progressBasisPoints < 0 || progressBasisPoints > 10_000) {
            throw new IllegalArgumentException("progressBasisPoints must be between 0 and 10000");
        }
        safeSummary = DomainText.required(safeSummary, "safeSummary", 2_000);
    }
}
