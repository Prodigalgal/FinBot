package io.omnnu.finbot.domain.workflow;

import java.time.Instant;
import java.util.Objects;

public record WorkflowAccepted(
        WorkflowEventId eventId,
        WorkflowRunId runId,
        long sequence,
        WorkflowType workflowType,
        Instant occurredAt) implements WorkflowEvent {

    public WorkflowAccepted {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(runId, "runId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        Objects.requireNonNull(workflowType, "workflowType");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
