package io.omnnu.finbot.application.catalog.dto;

import io.omnnu.finbot.domain.catalog.CatalogStatus;
import io.omnnu.finbot.domain.shared.DecimalValue;
import io.omnnu.finbot.domain.shared.DomainText;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record CatalogInstrumentSnapshot(
        String baseAsset,
        String quoteAsset,
        String symbol,
        String settlementAsset,
        BigDecimal contractSize,
        BigDecimal priceTick,
        BigDecimal quantityStep,
        BigDecimal minimumQuantity,
        BigDecimal maximumLeverage,
        CatalogStatus status,
        BigDecimal latestPrice,
        Instant observedAt) {
    public CatalogInstrumentSnapshot {
        baseAsset = DomainText.required(baseAsset, "baseAsset", 32).toUpperCase(java.util.Locale.ROOT);
        quoteAsset = DomainText.required(quoteAsset, "quoteAsset", 32).toUpperCase(java.util.Locale.ROOT);
        symbol = DomainText.required(symbol, "symbol", 48).toUpperCase(java.util.Locale.ROOT);
        settlementAsset = DomainText.required(settlementAsset, "settlementAsset", 32)
                .toUpperCase(java.util.Locale.ROOT);
        contractSize = DecimalValue.positive(contractSize, "contractSize");
        priceTick = DecimalValue.positive(priceTick, "priceTick");
        quantityStep = DecimalValue.positive(quantityStep, "quantityStep");
        minimumQuantity = DecimalValue.positive(minimumQuantity, "minimumQuantity");
        maximumLeverage = DecimalValue.positive(maximumLeverage, "maximumLeverage");
        if (maximumLeverage.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("maximumLeverage must be at least 1");
        }
        status = Objects.requireNonNull(status, "status");
        if (latestPrice != null && latestPrice.signum() <= 0) {
            throw new IllegalArgumentException("latestPrice must be positive when present");
        }
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }
}
