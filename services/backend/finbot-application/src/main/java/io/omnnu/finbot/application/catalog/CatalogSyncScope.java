package io.omnnu.finbot.application.catalog;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.MarketType;
import java.util.Objects;

public record CatalogSyncScope(ExchangeVenue exchange, MarketType marketType) {
    public CatalogSyncScope {
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(marketType, "marketType");
        if (marketType != MarketType.SPOT && marketType != MarketType.LINEAR_PERPETUAL) {
            throw new IllegalArgumentException("Catalog sync supports spot and linear perpetual markets only");
        }
    }
}
