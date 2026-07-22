package io.omnnu.finbot.application.research.port.out;

import io.omnnu.finbot.application.research.dto.ResearchWorkflowPlan;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;

@FunctionalInterface
public interface ResearchWorkflowPlanQuery {
    ResearchWorkflowPlan find(WorkflowRunId workflowRunId);
}
