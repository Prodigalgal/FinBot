package io.omnnu.finbot.application.workflow;

import io.omnnu.finbot.domain.workflow.AgentMessage;
import io.omnnu.finbot.domain.workflow.DebateId;
import io.omnnu.finbot.domain.workflow.DebateStatus;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WorkflowExecutionStore extends WorkflowRunFailureStore {
    Optional<WorkflowExecutionContext> load(WorkflowRunId runId);

    boolean markRunning(WorkflowRunId runId, Instant startedAt);

    boolean resumeFailed(WorkflowRunId runId, Instant resumedAt);

    void saveCheckpoint(WorkflowCheckpoint checkpoint);

    Optional<WorkflowCheckpoint> findCheckpoint(
            WorkflowRunId runId,
            io.omnnu.finbot.domain.workflow.WorkflowNodeId nodeId,
            int roundIndex,
            int iteration);

    void startDebate(DebateSession session);

    Optional<DebateSession> findDebate(WorkflowRunId runId);

    void updateDebate(DebateId debateId, DebateStatus status, int completedRounds, Instant completedAt);

    void saveMessage(AgentMessage message);

    List<AgentMessage> messages(DebateId debateId);

    void completeRun(WorkflowRunId runId, boolean partial, Instant completedAt);

}
