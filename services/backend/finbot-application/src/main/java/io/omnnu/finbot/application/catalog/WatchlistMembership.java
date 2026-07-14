package io.omnnu.finbot.application.catalog;

import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.catalog.WatchlistId;
import io.omnnu.finbot.domain.catalog.WatchlistResearchMode;

public record WatchlistMembership(
        WatchlistId watchlistId,
        String watchlistName,
        WatchlistResearchMode researchMode,
        InstrumentId preferredInstrumentId,
        String note) {
}
