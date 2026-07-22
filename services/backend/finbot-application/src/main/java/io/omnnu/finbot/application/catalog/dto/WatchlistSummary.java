package io.omnnu.finbot.application.catalog.dto;

import io.omnnu.finbot.domain.catalog.WatchlistId;
import java.time.Instant;

public record WatchlistSummary(
        WatchlistId watchlistId,
        String name,
        String description,
        boolean defaultWatchlist,
        int itemCount,
        long version,
        Instant updatedAt) {
}
