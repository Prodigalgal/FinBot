package io.omnnu.finbot.application.catalog.service;

import io.omnnu.finbot.application.catalog.dto.CatalogSyncResult;
import io.omnnu.finbot.application.catalog.dto.CatalogSyncRun;
import io.omnnu.finbot.application.catalog.dto.CatalogSyncScope;
import io.omnnu.finbot.application.catalog.port.in.ProductCatalogSyncUseCase;
import io.omnnu.finbot.application.catalog.port.out.ProductCatalogGateway;
import io.omnnu.finbot.application.catalog.port.out.ProductCatalogSyncStore;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

public final class ProductCatalogSyncService implements ProductCatalogSyncUseCase {
    private final ProductCatalogGateway gateway;
    private final ProductCatalogSyncStore store;
    private final Clock clock;

    public ProductCatalogSyncService(
            ProductCatalogGateway gateway,
            ProductCatalogSyncStore store,
            Clock clock) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CatalogSyncResult synchronize(String syncRunId, CatalogSyncScope scope) {
        Objects.requireNonNull(syncRunId, "syncRunId");
        Objects.requireNonNull(scope, "scope");
        store.start(syncRunId, scope, clock.instant());
        try {
            var snapshots = gateway.fetch(scope);
            if (snapshots.isEmpty()) {
                throw new IllegalStateException("Exchange catalog returned no instruments");
            }
            return store.complete(syncRunId, scope, snapshots, clock.instant());
        } catch (RuntimeException exception) {
            store.fail(
                    syncRunId,
                    exception.getClass().getSimpleName(),
                    safeMessage(exception),
                    clock.instant());
            throw exception;
        }
    }

    @Override
    public List<CatalogSyncRun> latestRuns() {
        return store.latestRuns();
    }

    private static String safeMessage(RuntimeException exception) {
        var value = Objects.requireNonNullElse(exception.getMessage(), exception.getClass().getSimpleName()).strip();
        return value.substring(0, Math.min(value.length(), 2_000));
    }
}
