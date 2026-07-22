package io.omnnu.finbot.application.market.dto;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import java.util.Objects;
import java.math.BigDecimal;

public record ResearchMarketScope(
        InstrumentId instrumentId,
        ExchangeVenue exchange,
        ExchangeEnvironment environment,
        String symbol,
        int intervalSeconds,
        int forecastHorizonSeconds,
        BigDecimal marketReferencePrice) {
    public ResearchMarketScope {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(symbol, "symbol");
        if (marketReferencePrice == null || marketReferencePrice.signum() <= 0) {
            throw new IllegalArgumentException("marketReferencePrice must be positive");
        }
    }
}
