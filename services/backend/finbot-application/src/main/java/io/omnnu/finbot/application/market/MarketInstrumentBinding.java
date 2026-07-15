package io.omnnu.finbot.application.market;

import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import java.util.Objects;

public record MarketInstrumentBinding(
        ResearchInstrument instrument,
        ExchangeEnvironment environment) {
    public MarketInstrumentBinding {
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(environment, "environment");
    }
}
