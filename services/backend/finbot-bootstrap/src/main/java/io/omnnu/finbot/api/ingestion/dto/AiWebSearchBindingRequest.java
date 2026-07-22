package io.omnnu.finbot.api.ingestion.dto;

import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.ingestion.AiWebSearchBinding;
import io.omnnu.finbot.domain.ingestion.AiWebSearchTool;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AiWebSearchBindingRequest(
        @NotBlank @Pattern(regexp = "^provider_[a-z0-9_-]{4,71}$") String providerProfileId,
        @NotBlank @Size(max = 160) String modelName,
        @NotNull ReasoningEffort reasoningEffort,
        @NotNull AiWebSearchTool tool) {
    AiWebSearchBinding toDomain() {
        return new AiWebSearchBinding(
                new AiProviderProfileId(providerProfileId),
                modelName,
                reasoningEffort,
                tool);
    }
}
