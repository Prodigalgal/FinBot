package io.omnnu.finbot.application.catalog;

import io.omnnu.finbot.domain.catalog.CatalogStatus;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.catalog.MarketType;
import java.math.BigDecimal;
import java.time.Instant;

public record VenueInstrumentView(
        InstrumentId instrumentId,
        ExchangeVenue exchange,
        MarketType marketType,
        String symbol,
        String settlementAsset,
        BigDecimal contractSize,
        BigDecimal priceTick,
        BigDecimal quantityStep,
        BigDecimal minimumQuantity,
        BigDecimal maximumLeverage,
        boolean executionEnabled,
        CatalogStatus status,
        Instant metadataUpdatedAt,
        BigDecimal latestPrice,
        Instant latestPriceAt) {
}
