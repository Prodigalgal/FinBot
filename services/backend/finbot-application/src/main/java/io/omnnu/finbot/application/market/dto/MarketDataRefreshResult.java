package io.omnnu.finbot.application.market.dto;

import io.omnnu.finbot.domain.catalog.InstrumentId;
import java.time.Instant;
import java.util.Objects;

public record MarketDataRefreshResult(
        InstrumentId instrumentId,
        int candleCount,
        Instant refreshedAt) {
    public MarketDataRefreshResult {
        Objects.requireNonNull(instrumentId, "instrumentId");
        if (candleCount < 1) {
            throw new IllegalArgumentException("candleCount must be positive");
        }
        Objects.requireNonNull(refreshedAt, "refreshedAt");
    }
}
