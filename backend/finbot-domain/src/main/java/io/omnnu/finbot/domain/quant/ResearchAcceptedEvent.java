package io.omnnu.finbot.domain.quant;

import io.omnnu.finbot.domain.shared.DomainText;
import java.time.Instant;

public record ResearchAcceptedEvent(
        QuantResearchEventId eventId,
        ResearchRunId researchRunId,
        long sequence,
        Instant occurredAt,
        String engineVersion,
        String inputFingerprint) implements QuantResearchEvent {

    public ResearchAcceptedEvent {
        QuantResearchEvent.requireEnvelope(eventId, researchRunId, sequence, occurredAt);
        engineVersion = DomainText.required(engineVersion, "engineVersion", 120);
        inputFingerprint = DomainText.required(inputFingerprint, "inputFingerprint", 200);
    }
}
