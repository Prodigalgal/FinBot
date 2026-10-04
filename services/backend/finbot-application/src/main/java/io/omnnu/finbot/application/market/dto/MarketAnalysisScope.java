package io.omnnu.finbot.application.market.dto;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import java.util.Objects;

public record MarketAnalysisScope(
        InstrumentId instrumentId,
        String symbol,
        ExchangeVenue exchange,
        ExchangeEnvironment environment,
        int intervalSeconds,
        int forecastHorizonSeconds) {

    public MarketAnalysisScope {
        Objects.requireNonNull(instrumentId, "instrumentId");
        symbol = io.omnnu.finbot.domain.shared.DomainText.symbol(symbol);
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(environment, "environment");
        if ((exchange == ExchangeVenue.GATE && environment == ExchangeEnvironment.DEMO)
                || (exchange == ExchangeVenue.BYBIT && environment == ExchangeEnvironment.TESTNET)) {
            throw new IllegalArgumentException("Market environment is not supported by the selected exchange");
        }
        if (intervalSeconds < 60 || intervalSeconds > 604_800) {
            throw new IllegalArgumentException("Market analysis interval must be between 60 and 604800 seconds");
        }
        if (forecastHorizonSeconds < intervalSeconds || forecastHorizonSeconds > 31_536_000) {
            throw new IllegalArgumentException(
                    "Forecast horizon must be at least one market interval and no more than one year");
        }
    }
}
