package io.omnnu.finbot.application.workflow;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;

@FunctionalInterface
public interface WorkflowRunFailureStore {
    boolean failRun(
            WorkflowRunId runId,
            String errorCode,
            String safeMessage,
            Instant failedAt);
}
