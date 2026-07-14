package io.omnnu.finbot.api.operations;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record UpdateScheduleRequest(
        boolean enabled,
        @Min(10) @Max(2_592_000) int intervalSeconds,
        @Min(0) long expectedVersion) {
}
