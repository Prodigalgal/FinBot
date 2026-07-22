package io.omnnu.finbot.application.ai.dto;

import io.omnnu.finbot.domain.ai.AiInvocationId;
import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.shared.DomainText;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record AiCompletionRequest(
        AiInvocationId invocationId,
        WorkflowRunId runId,
        WorkflowNodeId nodeId,
        AiProviderProfileId providerProfileId,
        AiProtocol protocol,
        String modelName,
        ReasoningEffort reasoningEffort,
        String systemPrompt,
        String userPrompt,
        int maximumOutputTokens,
        Duration timeout,
        Duration capacityWaitTimeout,
        Instant deadline,
        String promptVersion) {
    public AiCompletionRequest {
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(providerProfileId, "providerProfileId");
        Objects.requireNonNull(protocol, "protocol");
        modelName = DomainText.required(modelName, "modelName", 160);
        Objects.requireNonNull(reasoningEffort, "reasoningEffort");
        systemPrompt = DomainText.required(systemPrompt, "systemPrompt", 32_000);
        userPrompt = DomainText.required(userPrompt, "userPrompt", 200_000);
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(capacityWaitTimeout, "capacityWaitTimeout");
        Objects.requireNonNull(deadline, "deadline");
        promptVersion = DomainText.required(promptVersion, "promptVersion", 80);
        if (maximumOutputTokens < 64 || maximumOutputTokens > 65_536) {
            throw new IllegalArgumentException("maximumOutputTokens must be between 64 and 65536");
        }
        if (timeout.compareTo(Duration.ofSeconds(5)) < 0
                || timeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("timeout must be between five seconds and one hour");
        }
        if (capacityWaitTimeout.compareTo(Duration.ofSeconds(5)) < 0
                || capacityWaitTimeout.compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException("capacityWaitTimeout must be between five seconds and two hours");
        }
    }
}
