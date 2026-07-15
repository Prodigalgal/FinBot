package io.omnnu.finbot.application.workflow;

import java.time.Instant;

public record WorkflowNodeTestResult(
        String runId,
        String versionId,
        String nodeId,
        String status,
        String invocationId,
        String output,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant completedAt) {
}
