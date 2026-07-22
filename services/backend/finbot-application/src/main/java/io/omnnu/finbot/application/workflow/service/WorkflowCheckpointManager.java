package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.application.workflow.dto.WorkflowCheckpoint;
import io.omnnu.finbot.application.workflow.port.out.WorkflowExecutionStore;

import io.omnnu.finbot.application.operations.service.TaskCancellationContext;
import io.omnnu.finbot.domain.workflow.AgentMessage;
import io.omnnu.finbot.domain.workflow.WorkflowCheckpointStatus;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Clock;
import java.util.Objects;

final class WorkflowCheckpointManager {
    private final WorkflowExecutionStore executionStore;
    private final Clock clock;

    WorkflowCheckpointManager(WorkflowExecutionStore executionStore, Clock clock) {
        this.executionStore = Objects.requireNonNull(executionStore, "executionStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    void healCompleted(
            WorkflowRunId runId,
            WorkflowNodeDefinition node,
            int round,
            AgentMessage message) {
        var checkpoint = executionStore.findCheckpoint(runId, node.nodeId(), round, 0);
        if (checkpoint.isEmpty()
                || checkpoint.orElseThrow().status() != WorkflowCheckpointStatus.COMPLETED) {
            completed(runId, node, round, message.content().summary());
        }
    }

    void running(
            WorkflowRunId runId,
            WorkflowNodeDefinition node,
            int round,
            int attempt,
            WorkflowCheckpoint previous) {
        TaskCancellationContext.throwIfCancelled();
        var now = clock.instant();
        executionStore.saveCheckpoint(new WorkflowCheckpoint(
                WorkflowExecutionIds.checkpoint(runId, node.nodeId(), round),
                runId,
                node.nodeId(),
                round,
                0,
                attempt,
                WorkflowCheckpointStatus.RUNNING,
                null,
                null,
                null,
                previous == null || previous.startedAt() == null ? now : previous.startedAt(),
                null,
                now));
    }

    void completed(
            WorkflowRunId runId,
            WorkflowNodeDefinition node,
            int round,
            String summary) {
        TaskCancellationContext.throwIfCancelled();
        var previous = executionStore.findCheckpoint(runId, node.nodeId(), round, 0);
        var now = clock.instant();
        executionStore.saveCheckpoint(new WorkflowCheckpoint(
                WorkflowExecutionIds.checkpoint(runId, node.nodeId(), round),
                runId,
                node.nodeId(),
                round,
                0,
                previous.map(WorkflowCheckpoint::attempt).orElse(1),
                WorkflowCheckpointStatus.COMPLETED,
                summary,
                null,
                null,
                previous.map(WorkflowCheckpoint::startedAt).orElse(now),
                now,
                now));
    }

    void failed(
            WorkflowRunId runId,
            WorkflowNodeDefinition node,
            int round,
            String errorCode,
            String errorMessage) {
        TaskCancellationContext.throwIfCancelled();
        var previous = executionStore.findCheckpoint(runId, node.nodeId(), round, 0);
        var now = clock.instant();
        executionStore.saveCheckpoint(new WorkflowCheckpoint(
                WorkflowExecutionIds.checkpoint(runId, node.nodeId(), round),
                runId,
                node.nodeId(),
                round,
                0,
                previous.map(WorkflowCheckpoint::attempt)
                        .orElse(node.retryPolicy().maximumAttempts()
                                * (node.fallbackAiBinding() == null ? 1 : 2)),
                WorkflowCheckpointStatus.FAILED,
                null,
                errorCode,
                errorMessage,
                previous.map(WorkflowCheckpoint::startedAt).orElse(now),
                now,
                now));
    }

    void skipped(
            WorkflowRunId runId,
            WorkflowNodeDefinition node,
            int round,
            String summary) {
        TaskCancellationContext.throwIfCancelled();
        var existing = executionStore.findCheckpoint(runId, node.nodeId(), round, 0);
        if (existing.filter(value -> value.status().finalStatus()).isPresent()) {
            return;
        }
        var now = clock.instant();
        executionStore.saveCheckpoint(new WorkflowCheckpoint(
                WorkflowExecutionIds.checkpoint(runId, node.nodeId(), round),
                runId,
                node.nodeId(),
                round,
                0,
                1,
                WorkflowCheckpointStatus.SKIPPED,
                summary,
                null,
                null,
                now,
                now,
                now));
    }
}
