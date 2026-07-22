package io.omnnu.finbot.application.catalog.dto;

import io.omnnu.finbot.domain.catalog.WatchlistId;
import java.time.Instant;
import java.util.List;

public record WatchlistDetail(
        WatchlistId watchlistId,
        String name,
        String description,
        boolean defaultWatchlist,
        long version,
        Instant updatedAt,
        List<WatchlistItemView> items) {
    public WatchlistDetail {
        items = List.copyOf(items);
    }
}
