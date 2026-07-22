package io.omnnu.finbot.operations.handler;

import io.omnnu.finbot.application.catalog.port.in.ProductCatalogSyncUseCase;
import io.omnnu.finbot.application.operations.dto.BackgroundTask;
import io.omnnu.finbot.application.operations.port.in.BackgroundTaskHandler;
import io.omnnu.finbot.application.operations.dto.CatalogSyncTaskPayload;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.springframework.stereotype.Component;

@Component
public final class CatalogSyncTaskHandler implements BackgroundTaskHandler {
    private final ProductCatalogSyncUseCase catalogSync;

    public CatalogSyncTaskHandler(ProductCatalogSyncUseCase catalogSync) {
        this.catalogSync = Objects.requireNonNull(catalogSync, "catalogSync");
    }

    @Override
    public BackgroundTaskType taskType() {
        return BackgroundTaskType.CATALOG_SYNC;
    }

    @Override
    public CompletionStage<Void> handle(BackgroundTask task) {
        if (!(task.payload() instanceof CatalogSyncTaskPayload payload)) {
            throw new IllegalArgumentException("Catalog sync task has an invalid payload");
        }
        catalogSync.synchronize("catalogsync_" + task.taskId().value().substring("task_".length()), payload.scope());
        return CompletableFuture.completedFuture(null);
    }
}
