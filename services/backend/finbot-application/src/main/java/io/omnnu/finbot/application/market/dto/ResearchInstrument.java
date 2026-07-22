package io.omnnu.finbot.application.market.dto;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.catalog.MarketType;

public record ResearchInstrument(
        InstrumentId instrumentId,
        ExchangeVenue exchange,
        MarketType marketType,
        String symbol,
        String quoteCurrency) {
}
