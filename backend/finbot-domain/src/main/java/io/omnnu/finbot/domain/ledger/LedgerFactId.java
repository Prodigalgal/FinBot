package io.omnnu.finbot.domain.ledger;

import io.omnnu.finbot.domain.shared.DomainText;

public record LedgerFactId(String value) {
    public LedgerFactId {
        if (value == null) {
            throw new NullPointerException("value");
        }
        var separator = value.indexOf('_');
        if (separator < 1) {
            throw new IllegalArgumentException("Ledger fact identifier requires a prefix");
        }
        value = DomainText.identifier(value, value.substring(0, separator + 1));
    }
}
