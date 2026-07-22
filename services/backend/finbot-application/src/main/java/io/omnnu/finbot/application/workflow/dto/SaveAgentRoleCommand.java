package io.omnnu.finbot.application.workflow.dto;

import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.workflow.AgentRoleTemplateId;
import io.omnnu.finbot.domain.workflow.WorkflowOutputContract;

public record SaveAgentRoleCommand(
        AgentRoleTemplateId roleTemplateId,
        String displayName,
        String objective,
        String systemPrompt,
        String userPromptTemplate,
        WorkflowOutputContract outputContract,
        AiProviderProfileId defaultProviderProfileId,
        String defaultModelName,
        ReasoningEffort defaultReasoningEffort,
        Long expectedVersion) {
}
