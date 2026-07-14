package io.omnnu.finbot.application.workflow;

import io.omnnu.finbot.domain.workflow.WorkflowFailed;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;
import java.util.Objects;

public final class WorkflowRunFailureService implements WorkflowRunFailureUseCase {
    private final WorkflowRunFailureStore failureStore;
    private final WorkflowEventPublisher eventPublisher;

    public WorkflowRunFailureService(
            WorkflowRunFailureStore failureStore,
            WorkflowEventPublisher eventPublisher) {
        this.failureStore = Objects.requireNonNull(failureStore, "failureStore");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    @Override
    public boolean fail(
            WorkflowRunId runId,
            String errorCode,
            String safeMessage,
            boolean retryable,
            Instant failedAt) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(errorCode, "errorCode");
        Objects.requireNonNull(safeMessage, "safeMessage");
        Objects.requireNonNull(failedAt, "failedAt");
        if (!failureStore.failRun(runId, errorCode, safeMessage, failedAt)) {
            return false;
        }
        eventPublisher.publish(runId, (eventId, sequence, occurredAt) ->
                new WorkflowFailed(
                        eventId,
                        runId,
                        sequence,
                        errorCode,
                        safeMessage,
                        retryable,
                        occurredAt));
        return true;
    }
}
