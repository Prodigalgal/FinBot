package io.omnnu.finbot.api.workflow;

import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import java.time.Instant;

public record StartWorkflowResponse(
        String runId,
        String acceptedEventId,
        WorkflowRunStatus status,
        Instant acceptedAt,
        String statusUrl,
        String eventsUrl) {
}
