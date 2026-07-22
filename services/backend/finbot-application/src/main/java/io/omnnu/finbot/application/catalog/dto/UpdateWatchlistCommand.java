package io.omnnu.finbot.application.catalog.dto;

import io.omnnu.finbot.domain.catalog.WatchlistId;

public record UpdateWatchlistCommand(
        WatchlistId watchlistId,
        String name,
        String description,
        long expectedVersion) {
}
