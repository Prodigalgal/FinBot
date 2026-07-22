package io.omnnu.finbot.application.market.dto;

import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;

public record MarketDataSourceResult(
        InstrumentId instrumentId,
        String exchange,
        ExchangeEnvironment environment,
        String symbol,
        boolean success,
        int candleCount,
        String errorCode,
        String safeMessage) {
}
