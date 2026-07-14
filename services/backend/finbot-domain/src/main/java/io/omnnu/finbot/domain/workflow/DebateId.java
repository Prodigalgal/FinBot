package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.shared.DomainText;

public record DebateId(String value) {
    public DebateId {
        value = DomainText.identifier(value, "debate_");
    }
}
