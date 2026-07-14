package io.omnnu.finbot.api.catalog;

import io.omnnu.finbot.application.catalog.ProductDetail;
import io.omnnu.finbot.application.catalog.ProductPage;
import io.omnnu.finbot.application.catalog.ProductSummary;
import io.omnnu.finbot.application.catalog.VenueInstrumentView;
import io.omnnu.finbot.application.catalog.WatchlistDetail;
import io.omnnu.finbot.application.catalog.WatchlistItemView;
import io.omnnu.finbot.application.catalog.WatchlistMembership;
import io.omnnu.finbot.application.catalog.WatchlistSummary;
import io.omnnu.finbot.domain.catalog.CatalogStatus;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.MarketType;
import io.omnnu.finbot.domain.catalog.ProductCategory;
import io.omnnu.finbot.domain.catalog.WatchlistResearchMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class CatalogResponses {
    private CatalogResponses() {
    }

    public record ProductPageResponse(List<ProductSummaryResponse> products, String nextCursor) {
        public static ProductPageResponse from(ProductPage page) {
            return new ProductPageResponse(
                    page.products().stream().map(ProductSummaryResponse::from).toList(),
                    page.nextCursor() == null ? null : page.nextCursor().value());
        }
    }

    public record ProductSummaryResponse(
            String productId,
            String baseAsset,
            String quoteAsset,
            String displayName,
            ProductCategory category,
            CatalogStatus status,
            int instrumentCount,
            WatchlistResearchMode highestWatchlistMode) {
        static ProductSummaryResponse from(ProductSummary product) {
            return new ProductSummaryResponse(
                    product.productId().value(),
                    product.baseAsset(),
                    product.quoteAsset(),
                    product.displayName(),
                    product.category(),
                    product.status(),
                    product.instrumentCount(),
                    product.highestWatchlistMode());
        }
    }

    public record ProductDetailResponse(
            String productId,
            String baseAsset,
            String quoteAsset,
            String displayName,
            ProductCategory category,
            CatalogStatus status,
            List<InstrumentResponse> instruments,
            List<WatchlistMembershipResponse> watchlists) {
        public static ProductDetailResponse from(ProductDetail product) {
            return new ProductDetailResponse(
                    product.productId().value(),
                    product.baseAsset(),
                    product.quoteAsset(),
                    product.displayName(),
                    product.category(),
                    product.status(),
                    product.instruments().stream().map(InstrumentResponse::from).toList(),
                    product.watchlists().stream().map(WatchlistMembershipResponse::from).toList());
        }
    }

    public record InstrumentResponse(
            String instrumentId,
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
            Instant metadataUpdatedAt) {
        static InstrumentResponse from(VenueInstrumentView instrument) {
            return new InstrumentResponse(
                    instrument.instrumentId().value(),
                    instrument.exchange(),
                    instrument.marketType(),
                    instrument.symbol(),
                    instrument.settlementAsset(),
                    instrument.contractSize(),
                    instrument.priceTick(),
                    instrument.quantityStep(),
                    instrument.minimumQuantity(),
                    instrument.maximumLeverage(),
                    instrument.executionEnabled(),
                    instrument.status(),
                    instrument.metadataUpdatedAt());
        }
    }

    public record WatchlistMembershipResponse(
            String watchlistId,
            String watchlistName,
            WatchlistResearchMode researchMode,
            String preferredInstrumentId,
            String note) {
        static WatchlistMembershipResponse from(WatchlistMembership membership) {
            return new WatchlistMembershipResponse(
                    membership.watchlistId().value(),
                    membership.watchlistName(),
                    membership.researchMode(),
                    membership.preferredInstrumentId() == null
                            ? null
                            : membership.preferredInstrumentId().value(),
                    membership.note());
        }
    }

    public record WatchlistSummaryResponse(
            String watchlistId,
            String name,
            String description,
            boolean defaultWatchlist,
            int itemCount,
            long version,
            Instant updatedAt) {
        public static WatchlistSummaryResponse from(WatchlistSummary watchlist) {
            return new WatchlistSummaryResponse(
                    watchlist.watchlistId().value(),
                    watchlist.name(),
                    watchlist.description(),
                    watchlist.defaultWatchlist(),
                    watchlist.itemCount(),
                    watchlist.version(),
                    watchlist.updatedAt());
        }
    }

    public record WatchlistDetailResponse(
            String watchlistId,
            String name,
            String description,
            boolean defaultWatchlist,
            long version,
            Instant updatedAt,
            List<WatchlistItemResponse> items) {
        public static WatchlistDetailResponse from(WatchlistDetail watchlist) {
            return new WatchlistDetailResponse(
                    watchlist.watchlistId().value(),
                    watchlist.name(),
                    watchlist.description(),
                    watchlist.defaultWatchlist(),
                    watchlist.version(),
                    watchlist.updatedAt(),
                    watchlist.items().stream().map(WatchlistItemResponse::from).toList());
        }
    }

    public record WatchlistItemResponse(
            String productId,
            String displayName,
            String baseAsset,
            String quoteAsset,
            WatchlistResearchMode researchMode,
            String preferredInstrumentId,
            String note,
            Instant updatedAt) {
        static WatchlistItemResponse from(WatchlistItemView item) {
            return new WatchlistItemResponse(
                    item.productId().value(),
                    item.displayName(),
                    item.baseAsset(),
                    item.quoteAsset(),
                    item.researchMode(),
                    item.preferredInstrumentId() == null ? null : item.preferredInstrumentId().value(),
                    item.note(),
                    item.updatedAt());
        }
    }
}
