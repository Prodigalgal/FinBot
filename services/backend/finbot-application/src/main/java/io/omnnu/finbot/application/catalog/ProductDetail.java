package io.omnnu.finbot.application.catalog;

import io.omnnu.finbot.domain.catalog.CatalogStatus;
import io.omnnu.finbot.domain.catalog.ProductCategory;
import io.omnnu.finbot.domain.catalog.ProductId;
import java.util.List;

public record ProductDetail(
        ProductId productId,
        String baseAsset,
        String quoteAsset,
        String displayName,
        ProductCategory category,
        CatalogStatus status,
        List<VenueInstrumentView> instruments,
        List<WatchlistMembership> watchlists) {
    public ProductDetail {
        instruments = List.copyOf(instruments);
        watchlists = List.copyOf(watchlists);
    }
}
