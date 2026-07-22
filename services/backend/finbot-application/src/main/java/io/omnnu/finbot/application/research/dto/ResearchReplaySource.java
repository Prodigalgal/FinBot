package io.omnnu.finbot.application.research.dto;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;

public record ResearchReplaySource(
        WorkflowRunId runId,
        WorkflowType workflowType,
        WorkflowRunStatus status,
        WorkflowTrigger trigger,
        String requestSummary,
        WorkflowVersionId workflowVersionId,
        String workflowIdempotencyKey) {
}
