package io.omnnu.finbot.application.market.dto;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record MarketAnalysisScope(
        InstrumentId instrumentId,
        String symbol,
        ExchangeVenue exchange,
        ExchangeEnvironment environment,
        int intervalSeconds,
        int forecastHorizonSeconds) {
    private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9_-]{2,48}");

    public MarketAnalysisScope {
        Objects.requireNonNull(instrumentId, "instrumentId");
        symbol = Objects.requireNonNull(symbol, "symbol").strip().toUpperCase(Locale.ROOT);
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(environment, "environment");
        if ((exchange == ExchangeVenue.GATE && environment == ExchangeEnvironment.DEMO)
                || (exchange == ExchangeVenue.BYBIT && environment == ExchangeEnvironment.TESTNET)) {
            throw new IllegalArgumentException("Market environment is not supported by the selected exchange");
        }
        if (!SYMBOL.matcher(symbol).matches()) {
            throw new IllegalArgumentException("Invalid market analysis symbol");
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
