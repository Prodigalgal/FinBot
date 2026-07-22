package io.omnnu.finbot.api.configuration.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateSettingRequest(
        @NotNull @Size(max = 2000) String value,
        @PositiveOrZero long expectedVersion) {
}
