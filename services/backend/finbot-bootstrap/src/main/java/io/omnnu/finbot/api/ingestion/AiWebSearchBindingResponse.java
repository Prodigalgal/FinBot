package io.omnnu.finbot.api.ingestion;

import io.omnnu.finbot.domain.ingestion.AiWebSearchBinding;

public record AiWebSearchBindingResponse(
        String providerProfileId,
        String modelName,
        String reasoningEffort,
        String tool) {
    static AiWebSearchBindingResponse from(AiWebSearchBinding binding) {
        if (binding == null) {
            return null;
        }
        return new AiWebSearchBindingResponse(
                binding.providerProfileId().value(),
                binding.modelName(),
                binding.reasoningEffort().name(),
                binding.tool().name());
    }
}
