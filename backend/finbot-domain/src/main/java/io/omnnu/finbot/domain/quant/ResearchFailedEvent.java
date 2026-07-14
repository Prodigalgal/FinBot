package io.omnnu.finbot.domain.quant;

import io.omnnu.finbot.domain.shared.DomainText;
import java.time.Instant;
import java.util.Objects;

public record ResearchFailedEvent(
        QuantResearchEventId eventId,
        ResearchRunId researchRunId,
        long sequence,
        Instant occurredAt,
        ResearchErrorCode code,
        String safeMessage,
        boolean retryable) implements QuantResearchEvent {

    public ResearchFailedEvent {
        QuantResearchEvent.requireEnvelope(eventId, researchRunId, sequence, occurredAt);
        Objects.requireNonNull(code, "code");
        safeMessage = DomainText.required(safeMessage, "safeMessage", 2_000);
    }
}
