package io.omnnu.finbot.application.workflow.dto;

import io.omnnu.finbot.domain.workflow.WorkflowEventId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;
import java.util.Objects;

public record StartWorkflowResult(
        WorkflowRunId runId,
        WorkflowEventId acceptedEventId,
        Instant acceptedAt) {

    public StartWorkflowResult {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(acceptedEventId, "acceptedEventId");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
    }
}
