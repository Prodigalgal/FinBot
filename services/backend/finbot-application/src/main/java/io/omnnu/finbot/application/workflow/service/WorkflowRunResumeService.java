package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.application.workflow.exception.WorkflowNotFoundException;
import io.omnnu.finbot.application.workflow.port.in.WorkflowRunResumeUseCase;
import io.omnnu.finbot.application.workflow.port.out.WorkflowRunQuery;
import io.omnnu.finbot.application.workflow.port.out.WorkflowRunResumeStore;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import java.time.Clock;
import java.util.Objects;

public final class WorkflowRunResumeService implements WorkflowRunResumeUseCase {
    private final WorkflowRunResumeStore store;
    private final WorkflowRunQuery query;
    private final Clock clock;

    public WorkflowRunResumeService(
            WorkflowRunResumeStore store,
            WorkflowRunQuery query,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.query = Objects.requireNonNull(query, "query");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void resumeFailed(WorkflowRunId runId) {
        Objects.requireNonNull(runId, "runId");
        var current = query.find(runId)
                .orElseThrow(() -> new WorkflowNotFoundException(runId.value()));
        if (current.status() == WorkflowRunStatus.ACCEPTED
                || current.status() == WorkflowRunStatus.RUNNING) {
            return;
        }
        if (current.status() != WorkflowRunStatus.FAILED) {
            throw new IllegalStateException("Only a FAILED workflow run can be resumed");
        }
        if (!store.resumeFailed(runId, clock.instant())) {
            var raced = query.find(runId)
                    .orElseThrow(() -> new WorkflowNotFoundException(runId.value()));
            if (raced.status() != WorkflowRunStatus.ACCEPTED
                    && raced.status() != WorkflowRunStatus.RUNNING) {
                throw new IllegalStateException("Workflow run could not be resumed");
            }
        }
    }
}
