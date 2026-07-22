package io.omnnu.finbot.domain.consensus;

import io.omnnu.finbot.domain.shared.DomainText;

public record ConsensusDecisionId(String value) {
    public ConsensusDecisionId {
        value = DomainText.identifier(value, "decision_");
    }
}
