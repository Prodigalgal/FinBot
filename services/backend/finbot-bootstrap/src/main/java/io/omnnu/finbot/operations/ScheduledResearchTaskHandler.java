package io.omnnu.finbot.operations;

import io.omnnu.finbot.application.operations.BackgroundTask;
import io.omnnu.finbot.application.operations.BackgroundTaskHandler;
import io.omnnu.finbot.application.operations.ResearchTaskMode;
import io.omnnu.finbot.application.operations.ScheduledResearchTaskPayload;
import io.omnnu.finbot.application.research.ResearchPipelineRequest;
import io.omnnu.finbot.application.research.ResearchPipelineUseCase;
import io.omnnu.finbot.application.shared.IdempotencyKeys;
import io.omnnu.finbot.application.workflow.ActiveWorkflowQuery;
import io.omnnu.finbot.application.workflow.StartWorkflowCommand;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.springframework.stereotype.Component;

@Component
public final class ScheduledResearchTaskHandler implements BackgroundTaskHandler {
    private final ResearchPipelineUseCase researchPipeline;
    private final ActiveWorkflowQuery activeWorkflows;

    public ScheduledResearchTaskHandler(
            ResearchPipelineUseCase researchPipeline,
            ActiveWorkflowQuery activeWorkflows) {
        this.researchPipeline = Objects.requireNonNull(researchPipeline, "researchPipeline");
        this.activeWorkflows = Objects.requireNonNull(activeWorkflows, "activeWorkflows");
    }

    @Override
    public BackgroundTaskType taskType() {
        return BackgroundTaskType.SCHEDULED_RESEARCH;
    }

    @Override
    public CompletionStage<Void> handle(BackgroundTask task) {
        if (!(task.payload() instanceof ScheduledResearchTaskPayload payload)) {
            throw new IllegalArgumentException("Scheduled research task has an invalid payload");
        }
        var executions = activeWorkflows.activePublishedVersionIds().stream()
                .map(versionId -> {
                    var workflowCommand = new StartWorkflowCommand(
                            WorkflowType.SCHEDULED_RESEARCH,
                            WorkflowTrigger.SCHEDULED,
                            versionId,
                            payload.requestSummary(),
                            IdempotencyKeys.scoped(
                                    "scheduled-workflow",
                                    task.idempotencyKey() + ':' + versionId.value()));
                    return researchPipeline.execute(new ResearchPipelineRequest(
                                    workflowCommand,
                                    ResearchTaskMode.STANDARD.forAttempt(task.attemptCount()),
                                    task.attemptCount(),
                                    task.maximumAttempts()))
                            .toCompletableFuture();
                })
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(executions);
    }
}
