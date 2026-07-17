package io.omnnu.finbot.api.configuration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProbeProviderRequest(
        @NotBlank @Size(max = 1000) String baseUrl,
        @NotBlank @Size(min = 8, max = 16384) String apiKey,
        @Min(5) @Max(1800) int requestTimeoutSeconds) {
}
