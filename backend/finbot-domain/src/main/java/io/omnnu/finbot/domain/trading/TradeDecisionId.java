package io.omnnu.finbot.domain.trading;

import io.omnnu.finbot.domain.shared.DomainText;

public record TradeDecisionId(String value) {
    public TradeDecisionId {
        value = DomainText.identifier(value, "decision_");
    }
}
