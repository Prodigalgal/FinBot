package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.shared.DomainText;
import java.time.Instant;
import java.util.Objects;

public record AgentRoleTemplate(
        AgentRoleTemplateId roleTemplateId,
        String displayName,
        String objective,
        String systemPrompt,
        String userPromptTemplate,
        WorkflowOutputContract outputContract,
        AiProviderProfileId defaultProviderProfileId,
        String defaultModelName,
        ReasoningEffort defaultReasoningEffort,
        boolean builtIn,
        long version,
        Instant createdAt,
        Instant updatedAt) {
    public AgentRoleTemplate {
        Objects.requireNonNull(roleTemplateId, "roleTemplateId");
        displayName = DomainText.required(displayName, "displayName", 120);
        objective = DomainText.required(objective, "objective", 1000);
        systemPrompt = DomainText.required(systemPrompt, "systemPrompt", 32_000);
        userPromptTemplate = DomainText.required(userPromptTemplate, "userPromptTemplate", 32_000);
        Objects.requireNonNull(outputContract, "outputContract");
        Objects.requireNonNull(defaultProviderProfileId, "defaultProviderProfileId");
        defaultModelName = DomainText.required(defaultModelName, "defaultModelName", 160);
        Objects.requireNonNull(defaultReasoningEffort, "defaultReasoningEffort");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
