package io.omnnu.finbot.application.market;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record MarketCandle(
        InstrumentId instrumentId,
        ExchangeVenue exchange,
        String symbol,
        int intervalSeconds,
        Instant openTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        BigDecimal turnover,
        BigDecimal fundingRate,
        String sourceEndpoint,
        Instant observedAt) {
    public MarketCandle {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(openTime, "openTime");
        Objects.requireNonNull(open, "open");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(volume, "volume");
        fundingRate = fundingRate == null ? BigDecimal.ZERO : fundingRate;
        Objects.requireNonNull(sourceEndpoint, "sourceEndpoint");
        Objects.requireNonNull(observedAt, "observedAt");
        if (open.signum() <= 0 || high.signum() <= 0 || low.signum() <= 0 || close.signum() <= 0
                || volume.signum() < 0 || (turnover != null && turnover.signum() < 0)
                || high.compareTo(open.max(close).max(low)) < 0
                || low.compareTo(open.min(close).min(high)) > 0) {
            throw new IllegalArgumentException("Market candle values are inconsistent");
        }
    }
}
