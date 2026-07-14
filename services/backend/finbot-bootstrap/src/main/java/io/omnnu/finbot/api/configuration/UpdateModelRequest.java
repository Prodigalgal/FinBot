package io.omnnu.finbot.api.configuration;

import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record UpdateModelRequest(
        @NotNull ReasoningEffort defaultReasoningEffort,
        @NotNull @DecimalMin("0") BigDecimal inputUsdPerMillion,
        @NotNull @DecimalMin("0") BigDecimal outputUsdPerMillion,
        boolean enabled,
        @PositiveOrZero long expectedVersion) {
}
