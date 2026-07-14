package io.omnnu.finbot.api.catalog;

import io.omnnu.finbot.api.catalog.CatalogResponses.ProductDetailResponse;
import io.omnnu.finbot.api.catalog.CatalogResponses.ProductPageResponse;
import io.omnnu.finbot.api.catalog.CatalogResponses.WatchlistDetailResponse;
import io.omnnu.finbot.api.catalog.CatalogResponses.WatchlistSummaryResponse;
import io.omnnu.finbot.application.catalog.CatalogApplicationService;
import io.omnnu.finbot.application.catalog.CatalogUseCase;
import io.omnnu.finbot.application.catalog.CreateWatchlistCommand;
import io.omnnu.finbot.application.catalog.ProductSearchCriteria;
import io.omnnu.finbot.application.catalog.UpdateWatchlistCommand;
import io.omnnu.finbot.application.catalog.UpsertWatchlistItemCommand;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.catalog.MarketType;
import io.omnnu.finbot.domain.catalog.ProductCategory;
import io.omnnu.finbot.domain.catalog.ProductId;
import io.omnnu.finbot.domain.catalog.WatchlistId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
public final class CatalogController {
    private final CatalogUseCase catalogUseCase;

    public CatalogController(CatalogUseCase catalogUseCase) {
        this.catalogUseCase = Objects.requireNonNull(catalogUseCase, "catalogUseCase");
    }

    @GetMapping("/products")
    public ProductPageResponse products(
            @RequestParam(required = false) @Size(max = 160) String search,
            @RequestParam(required = false) ProductCategory category,
            @RequestParam(required = false) ExchangeVenue exchange,
            @RequestParam(required = false) MarketType marketType,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit) {
        var criteria = new ProductSearchCriteria(
                CatalogApplicationService.ADMIN_OWNER_ID,
                search,
                category,
                exchange,
                marketType,
                after == null || after.isBlank() ? null : new ProductId(after),
                limit);
        return ProductPageResponse.from(catalogUseCase.searchProducts(criteria));
    }

    @GetMapping("/products/{productId}")
    public ProductDetailResponse product(@PathVariable String productId) {
        return ProductDetailResponse.from(catalogUseCase.product(new ProductId(productId)));
    }

    @GetMapping("/watchlists")
    public List<WatchlistSummaryResponse> watchlists() {
        return catalogUseCase.watchlists().stream().map(WatchlistSummaryResponse::from).toList();
    }

    @PostMapping("/watchlists")
    @ResponseStatus(HttpStatus.CREATED)
    public WatchlistDetailResponse createWatchlist(@Valid @RequestBody CreateWatchlistRequest request) {
        return WatchlistDetailResponse.from(catalogUseCase.createWatchlist(
                new CreateWatchlistCommand(request.name(), request.description())));
    }

    @GetMapping("/watchlists/{watchlistId}")
    public WatchlistDetailResponse watchlist(@PathVariable String watchlistId) {
        return WatchlistDetailResponse.from(catalogUseCase.watchlist(new WatchlistId(watchlistId)));
    }

    @PutMapping("/watchlists/{watchlistId}")
    public WatchlistDetailResponse updateWatchlist(
            @PathVariable String watchlistId,
            @Valid @RequestBody UpdateWatchlistRequest request) {
        return WatchlistDetailResponse.from(catalogUseCase.updateWatchlist(new UpdateWatchlistCommand(
                new WatchlistId(watchlistId),
                request.name(),
                request.description(),
                request.expectedVersion())));
    }

    @DeleteMapping("/watchlists/{watchlistId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWatchlist(@PathVariable String watchlistId) {
        catalogUseCase.deleteWatchlist(new WatchlistId(watchlistId));
    }

    @PutMapping("/watchlists/{watchlistId}/items/{productId}")
    public WatchlistDetailResponse upsertWatchlistItem(
            @PathVariable String watchlistId,
            @PathVariable String productId,
            @Valid @RequestBody UpsertWatchlistItemRequest request) {
        return WatchlistDetailResponse.from(catalogUseCase.upsertWatchlistItem(
                new UpsertWatchlistItemCommand(
                        new WatchlistId(watchlistId),
                        new ProductId(productId),
                        request.preferredInstrumentId() == null || request.preferredInstrumentId().isBlank()
                                ? null
                                : new InstrumentId(request.preferredInstrumentId()),
                        request.researchMode(),
                        request.note())));
    }

    @DeleteMapping("/watchlists/{watchlistId}/items/{productId}")
    public WatchlistDetailResponse removeWatchlistItem(
            @PathVariable String watchlistId,
            @PathVariable String productId) {
        return WatchlistDetailResponse.from(catalogUseCase.removeWatchlistItem(
                new WatchlistId(watchlistId),
                new ProductId(productId)));
    }
}
