package io.omnnu.finbot.application.catalog.dto;

import java.time.Instant;

public record CatalogSyncResult(
        String syncRunId,
        CatalogSyncScope scope,
        int discoveredCount,
        int activeCount,
        int inactiveCount,
        Instant completedAt) {
}
