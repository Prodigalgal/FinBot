package io.omnnu.finbot.application.catalog;

import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.catalog.ProductId;
import io.omnnu.finbot.domain.catalog.WatchlistId;
import io.omnnu.finbot.domain.catalog.WatchlistResearchMode;

public record UpsertWatchlistItemCommand(
        WatchlistId watchlistId,
        ProductId productId,
        InstrumentId preferredInstrumentId,
        WatchlistResearchMode researchMode,
        String note) {
}
