package io.omnnu.finbot.application.operations.dto;

import io.omnnu.finbot.application.catalog.dto.CatalogSyncScope;
import java.util.Objects;

public record CatalogSyncTaskPayload(CatalogSyncScope scope) implements BackgroundTaskPayload {
    public CatalogSyncTaskPayload {
        Objects.requireNonNull(scope, "scope");
    }
}
