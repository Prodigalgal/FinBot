package io.omnnu.finbot.application.market.port.out;

import io.omnnu.finbot.application.market.dto.MarketCandle;
import io.omnnu.finbot.application.market.dto.ResearchInstrument;

import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import java.util.List;

@FunctionalInterface
public interface MarketDataGateway {
    List<MarketCandle> fetchCandles(
            ResearchInstrument instrument,
            ExchangeEnvironment environment,
            int intervalSeconds,
            int limit);
}
