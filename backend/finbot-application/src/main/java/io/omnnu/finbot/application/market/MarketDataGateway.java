package io.omnnu.finbot.application.market;

import java.util.List;

@FunctionalInterface
public interface MarketDataGateway {
    List<MarketCandle> fetchCandles(
            ResearchInstrument instrument,
            int intervalSeconds,
            int limit);
}
