package io.omnnu.finbot.domain.catalog;

import io.omnnu.finbot.domain.shared.DomainText;

public record WatchlistId(String value) {
    public WatchlistId {
        value = DomainText.identifier(value, "watchlist_");
    }
}
