package io.omnnu.finbot.application.market;

import java.util.List;

@FunctionalInterface
public interface MarketDataArtifactEncoder {
    EncodedMarketDataArtifact encode(List<MarketInstrumentSeries> series);
}
