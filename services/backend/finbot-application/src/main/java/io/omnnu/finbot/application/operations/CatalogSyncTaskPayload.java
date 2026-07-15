package io.omnnu.finbot.application.operations;

import io.omnnu.finbot.application.catalog.CatalogSyncScope;
import java.util.Objects;

public record CatalogSyncTaskPayload(CatalogSyncScope scope) implements BackgroundTaskPayload {
    public CatalogSyncTaskPayload {
        Objects.requireNonNull(scope, "scope");
    }
}
