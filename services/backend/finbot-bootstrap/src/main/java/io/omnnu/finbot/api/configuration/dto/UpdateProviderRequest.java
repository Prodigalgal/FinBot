package io.omnnu.finbot.api.configuration.dto;

import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.ReasoningParameterStyle;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateProviderRequest(
        @NotBlank @Size(max = 120) String displayName,
        @NotNull AiProtocol protocol,
        @NotNull ReasoningParameterStyle reasoningParameterStyle,
        @NotBlank @Size(max = 1000) String baseUrl,
        boolean enabled,
        @Min(1) @Max(60) int connectTimeoutSeconds,
        @Min(5) @Max(3600) int requestTimeoutSeconds,
        @Min(1) @Max(32) int maximumConcurrentRequests,
        @Min(5) @Max(7200) int acquireTimeoutSeconds,
        @PositiveOrZero long expectedVersion) {
}
