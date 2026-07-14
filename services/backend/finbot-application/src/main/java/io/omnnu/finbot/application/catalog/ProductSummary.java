package io.omnnu.finbot.application.catalog;

import io.omnnu.finbot.domain.catalog.CatalogStatus;
import io.omnnu.finbot.domain.catalog.ProductCategory;
import io.omnnu.finbot.domain.catalog.ProductId;
import io.omnnu.finbot.domain.catalog.WatchlistResearchMode;

public record ProductSummary(
        ProductId productId,
        String baseAsset,
        String quoteAsset,
        String displayName,
        ProductCategory category,
        CatalogStatus status,
        int instrumentCount,
        WatchlistResearchMode highestWatchlistMode) {
}
