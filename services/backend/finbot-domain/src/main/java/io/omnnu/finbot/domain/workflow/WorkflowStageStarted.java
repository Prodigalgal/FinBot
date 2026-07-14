package io.omnnu.finbot.domain.workflow;

import java.time.Instant;
import java.util.Objects;

public record WorkflowStageStarted(
        WorkflowEventId eventId,
        WorkflowRunId runId,
        long sequence,
        WorkflowStage stage,
        WorkflowNodeId nodeId,
        Instant occurredAt) implements WorkflowEvent {

    public WorkflowStageStarted {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(runId, "runId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
