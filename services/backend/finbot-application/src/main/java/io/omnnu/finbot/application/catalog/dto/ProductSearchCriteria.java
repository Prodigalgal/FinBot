package io.omnnu.finbot.application.catalog.dto;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.MarketType;
import io.omnnu.finbot.domain.catalog.ProductCategory;
import io.omnnu.finbot.domain.catalog.ProductId;

public record ProductSearchCriteria(
        String ownerId,
        String search,
        ProductCategory category,
        ExchangeVenue exchange,
        MarketType marketType,
        ProductId after,
        int limit) {
    public ProductSearchCriteria {
        ownerId = ownerId == null ? "" : ownerId.strip();
        search = search == null || search.isBlank() ? null : search.strip();
        if (ownerId.isEmpty()) {
            throw new IllegalArgumentException("ownerId must not be blank");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
    }
}
