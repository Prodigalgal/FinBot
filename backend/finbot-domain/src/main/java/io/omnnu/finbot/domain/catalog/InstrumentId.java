package io.omnnu.finbot.domain.catalog;

import io.omnnu.finbot.domain.shared.DomainText;

public record InstrumentId(String value) {
    public InstrumentId {
        value = DomainText.identifier(value, "instrument_");
    }
}
