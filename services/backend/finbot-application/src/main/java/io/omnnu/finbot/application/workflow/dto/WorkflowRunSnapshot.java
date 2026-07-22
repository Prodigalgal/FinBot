package io.omnnu.finbot.application.workflow.dto;

import io.omnnu.finbot.domain.shared.DomainText;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import java.time.Instant;
import java.util.Objects;

public record WorkflowRunSnapshot(
        WorkflowRunId runId,
        WorkflowType workflowType,
        WorkflowRunStatus status,
        WorkflowTrigger trigger,
        String requestSummary,
        Instant acceptedAt,
        Instant updatedAt) {

    public WorkflowRunSnapshot {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(workflowType, "workflowType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(trigger, "trigger");
        requestSummary = DomainText.required(requestSummary, "requestSummary", 2_000);
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
