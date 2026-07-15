package io.omnnu.finbot.api.ingestion;

import jakarta.validation.constraints.PositiveOrZero;

public record UpdateSourceStatusRequest(
        boolean enabled,
        @PositiveOrZero long expectedVersion) {
}
