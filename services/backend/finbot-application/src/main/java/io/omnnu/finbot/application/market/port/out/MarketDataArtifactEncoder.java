package io.omnnu.finbot.application.market.port.out;

import io.omnnu.finbot.application.market.dto.EncodedMarketDataArtifact;
import io.omnnu.finbot.application.market.dto.MarketInstrumentSeries;

import java.util.List;

@FunctionalInterface
public interface MarketDataArtifactEncoder {
    EncodedMarketDataArtifact encode(List<MarketInstrumentSeries> series);
}
