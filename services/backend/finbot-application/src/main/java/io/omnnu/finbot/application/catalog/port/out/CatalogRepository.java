package io.omnnu.finbot.application.catalog.port.out;

import io.omnnu.finbot.application.catalog.dto.ProductDetail;
import io.omnnu.finbot.application.catalog.dto.ProductPage;
import io.omnnu.finbot.application.catalog.dto.ProductSearchCriteria;
import io.omnnu.finbot.application.catalog.dto.WatchlistDetail;
import io.omnnu.finbot.application.catalog.dto.WatchlistSummary;

import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.catalog.ProductId;
import io.omnnu.finbot.domain.catalog.WatchlistId;
import io.omnnu.finbot.domain.catalog.WatchlistResearchMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CatalogRepository {
    ProductPage searchProducts(ProductSearchCriteria criteria);

    Optional<ProductDetail> findProduct(ProductId productId, String ownerId);

    List<WatchlistSummary> listWatchlists(String ownerId);

    Optional<WatchlistDetail> findWatchlist(WatchlistId watchlistId, String ownerId);

    void createWatchlist(
            WatchlistId watchlistId,
            String ownerId,
            String name,
            String description,
            Instant createdAt);

    boolean updateWatchlist(
            WatchlistId watchlistId,
            String ownerId,
            String name,
            String description,
            long expectedVersion,
            Instant updatedAt);

    boolean deleteWatchlist(WatchlistId watchlistId, String ownerId);

    boolean upsertWatchlistItem(
            WatchlistId watchlistId,
            String ownerId,
            ProductId productId,
            InstrumentId preferredInstrumentId,
            WatchlistResearchMode researchMode,
            String note,
            Instant updatedAt);

    boolean removeWatchlistItem(
            WatchlistId watchlistId,
            String ownerId,
            ProductId productId);
}
