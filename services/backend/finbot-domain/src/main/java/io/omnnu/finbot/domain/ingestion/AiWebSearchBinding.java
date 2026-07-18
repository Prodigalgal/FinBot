package io.omnnu.finbot.domain.ingestion;

import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.shared.DomainText;
import java.util.Objects;

public record AiWebSearchBinding(
        AiProviderProfileId providerProfileId,
        String modelName,
        ReasoningEffort reasoningEffort,
        AiWebSearchTool tool) {
    public AiWebSearchBinding {
        Objects.requireNonNull(providerProfileId, "providerProfileId");
        modelName = DomainText.required(modelName, "modelName", 160);
        Objects.requireNonNull(reasoningEffort, "reasoningEffort");
        Objects.requireNonNull(tool, "tool");
    }
}
