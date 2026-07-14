package io.omnnu.finbot.domain.ledger;

import io.omnnu.finbot.domain.shared.DomainText;

public record ExchangeAccountId(String value) {
    public ExchangeAccountId {
        value = DomainText.identifier(value, "account_");
    }
}
