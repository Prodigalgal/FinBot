package io.omnnu.finbot.application.workflow;

import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.application.market.ResearchMarketScope;

public record WorkflowExecutionContext(
        WorkflowRunId runId,
        WorkflowRunStatus status,
        String requestSummary,
        String researchContext,
        WorkflowDefinitionVersion definitionVersion,
        ResearchMarketScope marketScope) {
    public WorkflowExecutionContext(
            WorkflowRunId runId,
            WorkflowRunStatus status,
            String requestSummary,
            String researchContext,
            WorkflowDefinitionVersion definitionVersion) {
        this(runId, status, requestSummary, researchContext, definitionVersion, null);
    }

    public WorkflowExecutionContext {
        researchContext = researchContext == null ? "{}" : researchContext;
    }
}
