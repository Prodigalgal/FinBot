package io.omnnu.finbot.application.research;

import io.omnnu.finbot.application.operations.BackgroundTaskCoordinator;
import io.omnnu.finbot.application.operations.EnqueueTaskCommand;
import io.omnnu.finbot.application.operations.InstantResearchTaskPayload;
import io.omnnu.finbot.application.operations.ResearchTaskMode;
import io.omnnu.finbot.application.workflow.StartWorkflowCommand;
import io.omnnu.finbot.application.workflow.StartWorkflowUseCase;
import io.omnnu.finbot.application.workflow.WorkflowRunResumeUseCase;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class ResearchLaunchService implements ResearchLaunchUseCase {
    private static final int RESEARCH_PRIORITY = 90;
    private static final int MAXIMUM_ATTEMPTS = 5;

    private final StartWorkflowUseCase startWorkflow;
    private final BackgroundTaskCoordinator tasks;
    private final WorkflowRunResumeUseCase workflowResume;

    public ResearchLaunchService(
            StartWorkflowUseCase startWorkflow,
            BackgroundTaskCoordinator tasks,
            WorkflowRunResumeUseCase workflowResume) {
        this.startWorkflow = Objects.requireNonNull(startWorkflow, "startWorkflow");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.workflowResume = Objects.requireNonNull(workflowResume, "workflowResume");
    }

    @Override
    public CompletionStage<ResearchLaunchResult> launch(
            StartWorkflowCommand workflowCommand,
            String taskIdempotencyKey,
            ResearchTaskMode taskMode) {
        Objects.requireNonNull(workflowCommand, "workflowCommand");
        Objects.requireNonNull(taskMode, "taskMode");
        return startWorkflow.start(workflowCommand).thenApply(started -> {
            if (taskMode == ResearchTaskMode.RESUME_FAILED) {
                workflowResume.resumeFailed(started.runId());
            }
            var task = tasks.enqueue(new EnqueueTaskCommand(
                    BackgroundTaskType.INSTANT_RESEARCH,
                    taskIdempotencyKey,
                    new InstantResearchTaskPayload(
                            started.runId().value(),
                            workflowCommand.requestSummary(),
                            workflowCommand.workflowType(),
                            workflowCommand.trigger(),
                            workflowCommand.workflowVersionId(),
                            workflowCommand.idempotencyKey(),
                            taskMode),
                    RESEARCH_PRIORITY,
                    MAXIMUM_ATTEMPTS,
                    null));
            return new ResearchLaunchResult(started, task);
        });
    }
}
