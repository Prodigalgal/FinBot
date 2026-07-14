package io.omnnu.finbot.application.workflow;

import io.omnnu.finbot.application.shared.SortableIdGenerator;
import io.omnnu.finbot.domain.workflow.WorkflowAccepted;
import io.omnnu.finbot.domain.workflow.WorkflowEventId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public final class WorkflowRunApplicationService implements StartWorkflowUseCase {
    private final SortableIdGenerator idGenerator;
    private final WorkflowCommandStore commandStore;
    private final Clock clock;
    private final Executor executor;

    public WorkflowRunApplicationService(
            SortableIdGenerator idGenerator,
            WorkflowCommandStore commandStore,
            Clock clock,
            Executor executor) {
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.commandStore = Objects.requireNonNull(commandStore, "commandStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletionStage<StartWorkflowResult> start(StartWorkflowCommand command) {
        Objects.requireNonNull(command, "command");
        return CompletableFuture.supplyAsync(() -> accept(command), executor);
    }

    private StartWorkflowResult accept(StartWorkflowCommand command) {
        var runId = new WorkflowRunId(idGenerator.next("run_"));
        var eventId = new WorkflowEventId(idGenerator.next("event_"));
        var acceptedAt = clock.instant();
        var event = new WorkflowAccepted(eventId, runId, 1, command.workflowType(), acceptedAt);
        return commandStore.accept(command, event);
    }
}
