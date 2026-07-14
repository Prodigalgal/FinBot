package io.omnnu.finbot.api.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateWatchlistRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description,
        @PositiveOrZero long expectedVersion) {
}
