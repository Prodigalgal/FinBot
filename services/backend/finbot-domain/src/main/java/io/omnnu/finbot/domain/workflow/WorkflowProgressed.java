package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.shared.DomainText;
import java.time.Instant;
import java.util.Objects;

public record WorkflowProgressed(
        WorkflowEventId eventId,
        WorkflowRunId runId,
        long sequence,
        WorkflowStage stage,
        WorkflowNodeId nodeId,
        int progressPercent,
        String summary,
        Instant occurredAt) implements WorkflowEvent {

    public WorkflowProgressed {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(runId, "runId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(nodeId, "nodeId");
        if (progressPercent < 0 || progressPercent > 100) {
            throw new IllegalArgumentException("progressPercent must be between 0 and 100");
        }
        summary = DomainText.required(summary, "summary", 2_000);
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
