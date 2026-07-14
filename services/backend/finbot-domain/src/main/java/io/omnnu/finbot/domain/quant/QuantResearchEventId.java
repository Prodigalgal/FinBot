package io.omnnu.finbot.domain.quant;

import io.omnnu.finbot.domain.shared.DomainText;

public record QuantResearchEventId(String value) {
    public QuantResearchEventId {
        value = DomainText.identifier(value, "quant_event_");
    }
}
