package io.omnnu.finbot.domain.trading;

import io.omnnu.finbot.domain.shared.DomainText;

public record ApprovedTradeIntentId(String value) {
    public ApprovedTradeIntentId {
        value = DomainText.identifier(value, "intent_");
    }
}
