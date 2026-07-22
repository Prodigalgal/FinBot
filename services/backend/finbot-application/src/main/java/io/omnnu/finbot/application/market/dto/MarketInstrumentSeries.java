package io.omnnu.finbot.application.market.dto;

import java.util.List;

public record MarketInstrumentSeries(
        ResearchInstrument instrument,
        List<MarketCandle> candles) {
    public MarketInstrumentSeries {
        candles = List.copyOf(candles);
    }
}
