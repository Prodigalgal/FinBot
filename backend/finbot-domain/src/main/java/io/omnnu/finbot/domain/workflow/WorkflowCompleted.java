package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.shared.DomainText;
import java.time.Instant;
import java.util.Objects;

public record WorkflowCompleted(
        WorkflowEventId eventId,
        WorkflowRunId runId,
        long sequence,
        String resultReference,
        Instant occurredAt) implements WorkflowEvent {

    public WorkflowCompleted {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(runId, "runId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        resultReference = DomainText.required(resultReference, "resultReference", 500);
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
