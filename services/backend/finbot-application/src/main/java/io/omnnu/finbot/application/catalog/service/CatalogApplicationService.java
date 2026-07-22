package io.omnnu.finbot.application.catalog.service;

import io.omnnu.finbot.application.catalog.dto.CreateWatchlistCommand;
import io.omnnu.finbot.application.catalog.dto.ProductDetail;
import io.omnnu.finbot.application.catalog.dto.ProductPage;
import io.omnnu.finbot.application.catalog.dto.ProductSearchCriteria;
import io.omnnu.finbot.application.catalog.dto.UpdateWatchlistCommand;
import io.omnnu.finbot.application.catalog.dto.UpsertWatchlistItemCommand;
import io.omnnu.finbot.application.catalog.dto.WatchlistDetail;
import io.omnnu.finbot.application.catalog.dto.WatchlistSummary;
import io.omnnu.finbot.application.catalog.exception.CatalogConflictException;
import io.omnnu.finbot.application.catalog.exception.CatalogNotFoundException;
import io.omnnu.finbot.application.catalog.port.in.CatalogUseCase;
import io.omnnu.finbot.application.catalog.port.out.CatalogRepository;

import io.omnnu.finbot.application.shared.port.out.SortableIdGenerator;
import io.omnnu.finbot.domain.catalog.ProductId;
import io.omnnu.finbot.domain.catalog.WatchlistId;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

public final class CatalogApplicationService implements CatalogUseCase {
    public static final String ADMIN_OWNER_ID = "admin";

    private final CatalogRepository repository;
    private final SortableIdGenerator idGenerator;
    private final Clock clock;

    public CatalogApplicationService(
            CatalogRepository repository,
            SortableIdGenerator idGenerator,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ProductPage searchProducts(ProductSearchCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria");
        if (!ADMIN_OWNER_ID.equals(criteria.ownerId())) {
            throw new IllegalArgumentException("Unsupported catalog owner");
        }
        return repository.searchProducts(criteria);
    }

    @Override
    public ProductDetail product(ProductId productId) {
        return repository.findProduct(productId, ADMIN_OWNER_ID)
                .orElseThrow(() -> new CatalogNotFoundException("产品不存在"));
    }

    @Override
    public List<WatchlistSummary> watchlists() {
        return repository.listWatchlists(ADMIN_OWNER_ID);
    }

    @Override
    public WatchlistDetail watchlist(WatchlistId watchlistId) {
        return findWatchlist(watchlistId);
    }

    @Override
    public WatchlistDetail createWatchlist(CreateWatchlistCommand command) {
        Objects.requireNonNull(command, "command");
        var name = requireText(command.name(), "name", 120);
        var description = optionalText(command.description(), "description", 500);
        var watchlistId = new WatchlistId(idGenerator.next("watchlist_"));
        repository.createWatchlist(watchlistId, ADMIN_OWNER_ID, name, description, clock.instant());
        return findWatchlist(watchlistId);
    }

    @Override
    public WatchlistDetail updateWatchlist(UpdateWatchlistCommand command) {
        Objects.requireNonNull(command, "command");
        var existing = findWatchlist(command.watchlistId());
        var name = requireText(command.name(), "name", 120);
        var description = optionalText(command.description(), "description", 500);
        if (existing.defaultWatchlist() && name.isBlank()) {
            throw new IllegalArgumentException("默认 Watchlist 名称不能为空");
        }
        var updated = repository.updateWatchlist(
                command.watchlistId(),
                ADMIN_OWNER_ID,
                name,
                description,
                command.expectedVersion(),
                clock.instant());
        if (!updated) {
            throw new CatalogConflictException("Watchlist 已被修改，请刷新后重试");
        }
        return findWatchlist(command.watchlistId());
    }

    @Override
    public void deleteWatchlist(WatchlistId watchlistId) {
        var existing = findWatchlist(watchlistId);
        if (existing.defaultWatchlist()) {
            throw new IllegalArgumentException("默认 Watchlist 不允许删除");
        }
        if (!repository.deleteWatchlist(watchlistId, ADMIN_OWNER_ID)) {
            throw new CatalogNotFoundException("Watchlist 不存在");
        }
    }

    @Override
    public WatchlistDetail upsertWatchlistItem(UpsertWatchlistItemCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.researchMode(), "researchMode");
        findWatchlist(command.watchlistId());
        var product = product(command.productId());
        if (command.preferredInstrumentId() != null && product.instruments().stream()
                .noneMatch(instrument -> instrument.instrumentId().equals(command.preferredInstrumentId()))) {
            throw new IllegalArgumentException("首选合约不属于该产品");
        }
        var saved = repository.upsertWatchlistItem(
                command.watchlistId(),
                ADMIN_OWNER_ID,
                command.productId(),
                command.preferredInstrumentId(),
                command.researchMode(),
                optionalText(command.note(), "note", 500),
                clock.instant());
        if (!saved) {
            throw new CatalogNotFoundException("Watchlist 或产品不存在");
        }
        return findWatchlist(command.watchlistId());
    }

    @Override
    public WatchlistDetail removeWatchlistItem(WatchlistId watchlistId, ProductId productId) {
        findWatchlist(watchlistId);
        repository.removeWatchlistItem(watchlistId, ADMIN_OWNER_ID, productId);
        return findWatchlist(watchlistId);
    }

    private WatchlistDetail findWatchlist(WatchlistId watchlistId) {
        return repository.findWatchlist(watchlistId, ADMIN_OWNER_ID)
                .orElseThrow(() -> new CatalogNotFoundException("Watchlist 不存在"));
    }

    private static String requireText(String value, String fieldName, int maximumLength) {
        var normalized = Objects.requireNonNull(value, fieldName).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
        return normalized;
    }

    private static String optionalText(String value, String fieldName, int maximumLength) {
        var normalized = value == null ? "" : value.strip();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " is too long");
        }
        return normalized;
    }
}
