package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.shared.DomainText;
import java.util.Objects;

public record WorkflowNodeDefinition(
        WorkflowNodeId nodeId,
        WorkflowNodeType nodeType,
        String displayName,
        String roleName,
        AgentRoleTemplateId roleTemplateId,
        AiProviderProfileId providerProfileId,
        String modelName,
        ReasoningEffort reasoningEffort,
        String systemPrompt,
        String userPromptTemplate,
        WorkflowOutputContract outputContract,
        WorkflowContextMode contextMode,
        int contextHistoryRounds,
        int contextMaximumMessages,
        int maximumOutputTokens,
        int timeoutSeconds,
        WorkflowRetryPolicy retryPolicy,
        String operation,
        WorkflowCanvasPosition position,
        boolean enabled) {
    public WorkflowNodeDefinition {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(nodeType, "nodeType");
        displayName = DomainText.required(displayName, "displayName", 160);
        roleName = optional(roleName, 120);
        modelName = optional(modelName, 160);
        systemPrompt = optional(systemPrompt, 32_000);
        userPromptTemplate = optional(userPromptTemplate, 32_000);
        Objects.requireNonNull(contextMode, "contextMode");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        operation = optional(operation, 80);
        Objects.requireNonNull(position, "position");
        if (contextHistoryRounds < 0 || contextHistoryRounds > 8) {
            throw new IllegalArgumentException("contextHistoryRounds must be between 0 and 8");
        }
        if (contextMaximumMessages < 0 || contextMaximumMessages > 64) {
            throw new IllegalArgumentException("contextMaximumMessages must be between 0 and 64");
        }
        if (maximumOutputTokens < 64 || maximumOutputTokens > 65_536) {
            throw new IllegalArgumentException("maximumOutputTokens must be between 64 and 65536");
        }
        if (timeoutSeconds < 5 || timeoutSeconds > 1_800) {
            throw new IllegalArgumentException("timeoutSeconds must be between 5 and 1800");
        }
        requireLlmBinding(nodeType, providerProfileId, modelName, reasoningEffort, systemPrompt, outputContract);
    }

    private static void requireLlmBinding(
            WorkflowNodeType nodeType,
            AiProviderProfileId providerProfileId,
            String modelName,
            ReasoningEffort reasoningEffort,
            String systemPrompt,
            WorkflowOutputContract outputContract) {
        if (!nodeType.llmBacked()) {
            return;
        }
        Objects.requireNonNull(providerProfileId, "providerProfileId");
        Objects.requireNonNull(modelName, "modelName");
        Objects.requireNonNull(reasoningEffort, "reasoningEffort");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(outputContract, "outputContract");
    }

    private static String optional(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = value.strip();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException("Optional workflow node text is too long");
        }
        return normalized;
    }
}
