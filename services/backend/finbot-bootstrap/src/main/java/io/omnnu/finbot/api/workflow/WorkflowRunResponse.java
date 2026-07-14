package io.omnnu.finbot.api.workflow;

import io.omnnu.finbot.application.workflow.WorkflowRunSnapshot;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import java.time.Instant;

public record WorkflowRunResponse(
        String runId,
        WorkflowType workflowType,
        WorkflowRunStatus status,
        WorkflowTrigger trigger,
        String requestSummary,
        Instant acceptedAt,
        Instant updatedAt,
        String eventsUrl) {

    static WorkflowRunResponse from(WorkflowRunSnapshot snapshot) {
        var runId = snapshot.runId().value();
        return new WorkflowRunResponse(
                runId,
                snapshot.workflowType(),
                snapshot.status(),
                snapshot.trigger(),
                snapshot.requestSummary(),
                snapshot.acceptedAt(),
                snapshot.updatedAt(),
                "/api/v2/workflows/" + runId + "/events");
    }
}
