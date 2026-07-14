package io.omnnu.finbot.application.catalog;

import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.catalog.ProductId;
import io.omnnu.finbot.domain.catalog.WatchlistResearchMode;
import java.time.Instant;

public record WatchlistItemView(
        ProductId productId,
        String displayName,
        String baseAsset,
        String quoteAsset,
        WatchlistResearchMode researchMode,
        InstrumentId preferredInstrumentId,
        String note,
        Instant updatedAt) {
}
