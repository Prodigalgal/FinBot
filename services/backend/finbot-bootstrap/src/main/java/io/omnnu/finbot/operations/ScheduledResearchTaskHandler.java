package io.omnnu.finbot.operations;

import io.omnnu.finbot.application.operations.BackgroundTask;
import io.omnnu.finbot.application.operations.BackgroundTaskHandler;
import io.omnnu.finbot.application.operations.ResearchTaskMode;
import io.omnnu.finbot.application.operations.ScheduledResearchTaskPayload;
import io.omnnu.finbot.application.research.ResearchPipelineRequest;
import io.omnnu.finbot.application.research.ResearchPipelineUseCase;
import io.omnnu.finbot.application.workflow.StartWorkflowCommand;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.springframework.stereotype.Component;

@Component
public final class ScheduledResearchTaskHandler implements BackgroundTaskHandler {
    private final ResearchPipelineUseCase researchPipeline;

    public ScheduledResearchTaskHandler(ResearchPipelineUseCase researchPipeline) {
        this.researchPipeline = Objects.requireNonNull(researchPipeline, "researchPipeline");
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
        var workflowCommand = new StartWorkflowCommand(
                WorkflowType.SCHEDULED_RESEARCH,
                WorkflowTrigger.SCHEDULED,
                null,
                payload.requestSummary(),
                task.idempotencyKey());
        return researchPipeline.execute(new ResearchPipelineRequest(
                        workflowCommand,
                        ResearchTaskMode.STANDARD,
                        task.attemptCount(),
                        task.maximumAttempts()))
                .thenApply(ignored -> null);
    }
}
