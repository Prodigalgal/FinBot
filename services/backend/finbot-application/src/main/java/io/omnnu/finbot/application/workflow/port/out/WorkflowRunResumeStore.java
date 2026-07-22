package io.omnnu.finbot.application.workflow.port.out;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;

@FunctionalInterface
public interface WorkflowRunResumeStore {
    boolean resumeFailed(WorkflowRunId runId, Instant resumedAt);
}
