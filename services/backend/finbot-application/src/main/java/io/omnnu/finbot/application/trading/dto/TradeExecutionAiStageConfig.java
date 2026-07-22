package io.omnnu.finbot.application.trading.dto;

import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.workflow.WorkflowRetryPolicy;
import java.util.Objects;

public record TradeExecutionAiStageConfig(
        TradeExecutionAiStage stage,
        AiModelBinding primaryAiBinding,
        AiModelBinding fallbackAiBinding,
        String systemPrompt,
        String userPromptTemplate,
        int maximumOutputTokens,
        int timeoutSeconds,
        WorkflowRetryPolicy retryPolicy,
        boolean enabled,
        long version) {
    public TradeExecutionAiStageConfig {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(primaryAiBinding, "primaryAiBinding");
        systemPrompt = requireText(systemPrompt, "systemPrompt");
        userPromptTemplate = requireText(userPromptTemplate, "userPromptTemplate");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        if (maximumOutputTokens < 256 || maximumOutputTokens > 65_536) {
            throw new IllegalArgumentException("maximumOutputTokens must be between 256 and 65536");
        }
        if (timeoutSeconds < 10 || timeoutSeconds > 3_600 || version < 0) {
            throw new IllegalArgumentException("Invalid execution AI stage limits");
        }
    }

    private static String requireText(String value, String field) {
        var normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
