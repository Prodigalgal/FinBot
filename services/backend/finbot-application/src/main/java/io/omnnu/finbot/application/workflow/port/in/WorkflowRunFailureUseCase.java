package io.omnnu.finbot.application.workflow.port.in;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;

@FunctionalInterface
public interface WorkflowRunFailureUseCase {
    boolean fail(
            WorkflowRunId runId,
            String errorCode,
            String safeMessage,
            boolean retryable,
            Instant failedAt);
}
