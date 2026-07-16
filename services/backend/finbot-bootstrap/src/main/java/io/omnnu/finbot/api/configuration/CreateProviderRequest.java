package io.omnnu.finbot.api.configuration;

import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.configuration.ReasoningParameterStyle;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateProviderRequest(
        @NotBlank @Size(max = 120) String displayName,
        @NotNull AiProtocol protocol,
        @NotNull ReasoningParameterStyle reasoningParameterStyle,
        @NotBlank @Size(max = 1000) String baseUrl,
        boolean enabled,
        @Min(1) @Max(60) int connectTimeoutSeconds,
        @Min(5) @Max(1800) int requestTimeoutSeconds,
        @NotBlank @Size(max = 160) String initialModelName,
        @NotNull ReasoningEffort defaultReasoningEffort,
        @NotNull ReasoningEffort maximumReasoningEffort,
        @NotNull @DecimalMin("0") BigDecimal inputUsdPerMillion,
        @NotNull @DecimalMin("0") BigDecimal outputUsdPerMillion) {
}
