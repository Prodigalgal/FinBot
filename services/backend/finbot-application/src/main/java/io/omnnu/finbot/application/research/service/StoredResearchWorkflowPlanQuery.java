package io.omnnu.finbot.application.research.service;

import io.omnnu.finbot.application.research.dto.ResearchWorkflowPlan;
import io.omnnu.finbot.application.research.port.out.ResearchWorkflowPlanQuery;

import io.omnnu.finbot.application.workflow.port.out.WorkflowExecutionStore;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.Objects;

public final class StoredResearchWorkflowPlanQuery implements ResearchWorkflowPlanQuery {
    private final WorkflowExecutionStore workflowStore;

    public StoredResearchWorkflowPlanQuery(WorkflowExecutionStore workflowStore) {
        this.workflowStore = Objects.requireNonNull(workflowStore, "workflowStore");
    }

    @Override
    public ResearchWorkflowPlan find(WorkflowRunId workflowRunId) {
        var execution = workflowStore.load(workflowRunId)
                .orElseThrow(() -> new IllegalStateException("Accepted workflow run is missing"));
        return ResearchWorkflowPlan.from(execution.definitionVersion());
    }
}
