package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.shared.DomainText;
import java.time.Instant;
import java.util.Objects;

public record WorkflowFailed(
        WorkflowEventId eventId,
        WorkflowRunId runId,
        long sequence,
        String errorCode,
        String safeMessage,
        boolean retryable,
        Instant occurredAt) implements WorkflowEvent {

    public WorkflowFailed {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(runId, "runId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        errorCode = DomainText.required(errorCode, "errorCode", 120);
        safeMessage = DomainText.required(safeMessage, "safeMessage", 2_000);
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
