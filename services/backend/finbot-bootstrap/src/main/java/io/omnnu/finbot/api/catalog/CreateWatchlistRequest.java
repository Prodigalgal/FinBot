package io.omnnu.finbot.api.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWatchlistRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description) {
}
