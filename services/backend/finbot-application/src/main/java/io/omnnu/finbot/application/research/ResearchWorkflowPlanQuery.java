package io.omnnu.finbot.application.research;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;

@FunctionalInterface
public interface ResearchWorkflowPlanQuery {
    ResearchWorkflowPlan find(WorkflowRunId workflowRunId);
}
