package io.omnnu.finbot.api.ingestion.dto;

import jakarta.validation.constraints.PositiveOrZero;

public record UpdateSourceStatusRequest(
        boolean enabled,
        @PositiveOrZero long expectedVersion) {
}
