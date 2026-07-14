package io.omnnu.finbot.application.workflow;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.Optional;

@FunctionalInterface
public interface WorkflowRunQuery {
    Optional<WorkflowRunSnapshot> find(WorkflowRunId runId);
}
