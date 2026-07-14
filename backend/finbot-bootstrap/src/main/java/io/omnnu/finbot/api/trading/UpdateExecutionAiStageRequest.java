package io.omnnu.finbot.api.trading;

import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateExecutionAiStageRequest(
        @NotBlank @Size(max = 80) String providerProfileId,
        @NotBlank @Size(max = 160) String modelName,
        @NotNull ReasoningEffort reasoningEffort,
        @NotBlank @Size(max = 20_000) String systemPrompt,
        @NotBlank @Size(max = 20_000) String userPromptTemplate,
        @Min(256) @Max(16_384) int maximumOutputTokens,
        @Min(10) @Max(1_800) int timeoutSeconds,
        boolean enabled,
        @Min(0) long expectedVersion) {
}
