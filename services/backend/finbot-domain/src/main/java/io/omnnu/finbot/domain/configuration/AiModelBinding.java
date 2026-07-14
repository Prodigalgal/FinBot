package io.omnnu.finbot.domain.configuration;

import io.omnnu.finbot.domain.shared.DomainText;
import java.util.Objects;

public record AiModelBinding(
        AiProviderProfileId providerProfileId,
        String modelName,
        ReasoningEffort reasoningEffort) {
    public AiModelBinding {
        Objects.requireNonNull(providerProfileId, "providerProfileId");
        modelName = DomainText.required(modelName, "modelName", 160);
        Objects.requireNonNull(reasoningEffort, "reasoningEffort");
    }
}
