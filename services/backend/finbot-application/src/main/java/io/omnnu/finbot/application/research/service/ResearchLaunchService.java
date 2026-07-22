package io.omnnu.finbot.application.research.service;

import io.omnnu.finbot.application.research.dto.ResearchLaunchResult;
import io.omnnu.finbot.application.research.port.in.ResearchLaunchUseCase;

import io.omnnu.finbot.application.market.dto.MarketAnalysisScope;
import io.omnnu.finbot.application.operations.service.BackgroundTaskCoordinator;
import io.omnnu.finbot.application.operations.dto.EnqueueTaskCommand;
import io.omnnu.finbot.application.operations.dto.InstantResearchTaskPayload;
import io.omnnu.finbot.application.operations.dto.ResearchTaskMode;
import io.omnnu.finbot.application.workflow.dto.StartWorkflowCommand;
import io.omnnu.finbot.application.workflow.port.in.StartWorkflowUseCase;
import io.omnnu.finbot.application.workflow.port.in.WorkflowRunResumeUseCase;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
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
        return launch(workflowCommand, taskIdempotencyKey, taskMode, null, null);
    }

    @Override
    public CompletionStage<ResearchLaunchResult> launch(
            StartWorkflowCommand workflowCommand,
            String taskIdempotencyKey,
            ResearchTaskMode taskMode,
            MarketAnalysisScope marketAnalysisScope) {
        return launch(workflowCommand, taskIdempotencyKey, taskMode, marketAnalysisScope, null);
    }

    @Override
    public CompletionStage<ResearchLaunchResult> launch(
            StartWorkflowCommand workflowCommand,
            String taskIdempotencyKey,
            ResearchTaskMode taskMode,
            MarketAnalysisScope marketAnalysisScope,
            WorkflowVersionId demoWorkflowVersionId) {
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
                            demoWorkflowVersionId,
                            workflowCommand.idempotencyKey(),
                            taskMode,
                            marketAnalysisScope),
                    RESEARCH_PRIORITY,
                    MAXIMUM_ATTEMPTS,
                    null));
            return new ResearchLaunchResult(started, task);
        });
    }
}
