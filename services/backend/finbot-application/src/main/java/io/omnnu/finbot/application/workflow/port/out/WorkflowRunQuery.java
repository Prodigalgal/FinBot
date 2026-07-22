package io.omnnu.finbot.application.workflow.port.out;

import io.omnnu.finbot.application.workflow.dto.WorkflowRunSnapshot;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.Optional;

@FunctionalInterface
public interface WorkflowRunQuery {
    Optional<WorkflowRunSnapshot> find(WorkflowRunId runId);
}
