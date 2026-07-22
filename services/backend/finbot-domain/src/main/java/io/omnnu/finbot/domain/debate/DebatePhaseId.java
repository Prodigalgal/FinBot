package io.omnnu.finbot.domain.debate;

import io.omnnu.finbot.domain.shared.DomainText;

public record DebatePhaseId(String value) {
    public DebatePhaseId {
        value = DomainText.identifier(value, "phase_");
    }
}
