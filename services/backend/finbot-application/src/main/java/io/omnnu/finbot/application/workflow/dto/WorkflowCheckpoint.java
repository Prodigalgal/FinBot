package io.omnnu.finbot.application.workflow.dto;

import io.omnnu.finbot.domain.workflow.WorkflowCheckpointId;
import io.omnnu.finbot.domain.workflow.WorkflowCheckpointStatus;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;

public record WorkflowCheckpoint(
        WorkflowCheckpointId checkpointId,
        WorkflowRunId runId,
        WorkflowNodeId nodeId,
        int roundIndex,
        int iteration,
        int attempt,
        WorkflowCheckpointStatus status,
        String resultSummary,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt) {
}
