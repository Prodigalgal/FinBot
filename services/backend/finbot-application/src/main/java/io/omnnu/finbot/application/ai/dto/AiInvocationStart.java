package io.omnnu.finbot.application.ai.dto;

import io.omnnu.finbot.domain.ai.AiInvocationId;
import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;

public record AiInvocationStart(
        AiInvocationId invocationId,
        WorkflowRunId runId,
        WorkflowNodeId nodeId,
        AiProviderProfileId providerProfileId,
        AiProtocol protocol,
        String modelName,
        ReasoningEffort reasoningEffort,
        String promptVersion,
        String requestHash,
        Instant startedAt) {
}
