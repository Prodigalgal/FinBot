package io.omnnu.finbot.application.workflow;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;

@FunctionalInterface
public interface WorkflowRunResumeUseCase {
    void resumeFailed(WorkflowRunId runId);
}
