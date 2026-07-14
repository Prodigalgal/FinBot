package io.omnnu.finbot.application.catalog;

import io.omnnu.finbot.domain.catalog.ProductId;
import io.omnnu.finbot.domain.catalog.WatchlistId;
import java.util.List;

public interface CatalogUseCase {
    ProductPage searchProducts(ProductSearchCriteria criteria);

    ProductDetail product(ProductId productId);

    List<WatchlistSummary> watchlists();

    WatchlistDetail watchlist(WatchlistId watchlistId);

    WatchlistDetail createWatchlist(CreateWatchlistCommand command);

    WatchlistDetail updateWatchlist(UpdateWatchlistCommand command);

    void deleteWatchlist(WatchlistId watchlistId);

    WatchlistDetail upsertWatchlistItem(UpsertWatchlistItemCommand command);

    WatchlistDetail removeWatchlistItem(WatchlistId watchlistId, ProductId productId);
}
