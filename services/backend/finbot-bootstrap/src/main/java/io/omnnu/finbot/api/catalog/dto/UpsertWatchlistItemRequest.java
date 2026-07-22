package io.omnnu.finbot.api.catalog.dto;

import io.omnnu.finbot.domain.catalog.WatchlistResearchMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpsertWatchlistItemRequest(
        @Size(max = 80) String preferredInstrumentId,
        @NotNull WatchlistResearchMode researchMode,
        @Size(max = 500) String note) {
}
