package io.omnnu.finbot.application.catalog.dto;

import java.time.Instant;

public record CatalogSyncRun(
        String syncRunId,
        CatalogSyncScope scope,
        String status,
        int discoveredCount,
        int activeCount,
        int inactiveCount,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant completedAt) {
}
