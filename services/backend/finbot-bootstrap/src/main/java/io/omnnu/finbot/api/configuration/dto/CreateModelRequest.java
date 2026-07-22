package io.omnnu.finbot.api.configuration.dto;

import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateModelRequest(
        @NotBlank @Size(max = 80) String providerProfileId,
        @NotBlank @Size(max = 160) String modelName,
        @NotNull ReasoningEffort defaultReasoningEffort,
        @NotNull ReasoningEffort maximumReasoningEffort,
        @NotNull @DecimalMin("0") BigDecimal inputUsdPerMillion,
        @NotNull @DecimalMin("0") BigDecimal outputUsdPerMillion,
        boolean enabled) {
}
