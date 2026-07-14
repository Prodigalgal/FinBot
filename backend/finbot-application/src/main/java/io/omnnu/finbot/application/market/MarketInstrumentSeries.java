package io.omnnu.finbot.application.market;

import java.util.List;

public record MarketInstrumentSeries(
        ResearchInstrument instrument,
        List<MarketCandle> candles) {
    public MarketInstrumentSeries {
        candles = List.copyOf(candles);
    }
}
