package io.omnnu.finbot.application.workflow;

import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;

public record WorkflowExecutionContext(
        WorkflowRunId runId,
        WorkflowRunStatus status,
        String requestSummary,
        String researchContext,
        WorkflowDefinitionVersion definitionVersion) {
    public WorkflowExecutionContext {
        researchContext = researchContext == null ? "{}" : researchContext;
    }
}
