package io.omnnu.finbot.domain.quant;

import io.omnnu.finbot.domain.shared.DomainText;

public record ResearchRunId(String value) {
    public ResearchRunId {
        value = DomainText.identifier(value, "research_");
    }
}
