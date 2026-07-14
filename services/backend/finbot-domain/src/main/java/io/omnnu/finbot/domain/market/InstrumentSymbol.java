package io.omnnu.finbot.domain.market;

import io.omnnu.finbot.domain.shared.DomainText;

public record InstrumentSymbol(String value) {
    public InstrumentSymbol {
        value = DomainText.symbol(value);
    }
}
