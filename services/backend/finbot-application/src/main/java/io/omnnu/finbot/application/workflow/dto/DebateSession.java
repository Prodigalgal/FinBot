package io.omnnu.finbot.application.workflow.dto;

import io.omnnu.finbot.domain.workflow.DebateId;
import io.omnnu.finbot.domain.workflow.DebateStatus;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;

public record DebateSession(
        DebateId debateId,
        WorkflowRunId runId,
        DebateStatus status,
        int configuredRounds,
        int completedRounds,
        WorkflowNodeId decisionNodeId,
        Instant startedAt,
        Instant completedAt) {
}
