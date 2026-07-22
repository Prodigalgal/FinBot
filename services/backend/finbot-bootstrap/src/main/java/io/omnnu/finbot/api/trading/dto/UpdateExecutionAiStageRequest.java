package io.omnnu.finbot.api.trading.dto;

import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateExecutionAiStageRequest(
        @NotNull @Valid AiBindingRequest primaryAiBinding,
        @Valid AiBindingRequest fallbackAiBinding,
        @NotBlank @Size(max = 20_000) String systemPrompt,
        @NotBlank @Size(max = 20_000) String userPromptTemplate,
        @Min(256) @Max(65_536) int maximumOutputTokens,
        @Min(10) @Max(3_600) int timeoutSeconds,
        @Min(1) @Max(5) int retryMaximumAttempts,
        @Min(0) @Max(300) int retryBackoffSeconds,
        boolean enabled,
        @Min(0) long expectedVersion) {
    public record AiBindingRequest(
            @NotBlank @Size(max = 80) String providerProfileId,
            @NotBlank @Size(max = 160) String modelName,
            @NotNull ReasoningEffort reasoningEffort) {
        public AiModelBinding toDomain() {
            return new AiModelBinding(
                    new AiProviderProfileId(providerProfileId),
                    modelName,
                    reasoningEffort);
        }
    }
}
