package io.omnnu.finbot.application.market;

import io.omnnu.finbot.domain.catalog.InstrumentId;

public record MarketDataSourceResult(
        InstrumentId instrumentId,
        String exchange,
        String symbol,
        boolean success,
        int candleCount,
        String errorCode,
        String safeMessage) {
}
