package io.omnnu.finbot.operations;

import io.omnnu.finbot.application.operations.BackgroundTask;
import io.omnnu.finbot.application.operations.BackgroundTaskHandler;
import io.omnnu.finbot.application.operations.InstantResearchTaskPayload;
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
public final class InstantResearchTaskHandler implements BackgroundTaskHandler {
    private final ResearchPipelineUseCase researchPipeline;

    public InstantResearchTaskHandler(ResearchPipelineUseCase researchPipeline) {
        this.researchPipeline = Objects.requireNonNull(researchPipeline, "researchPipeline");
    }

    @Override
    public BackgroundTaskType taskType() {
        return BackgroundTaskType.INSTANT_RESEARCH;
    }

    @Override
    public CompletionStage<Void> handle(BackgroundTask task) {
        if (!(task.payload() instanceof InstantResearchTaskPayload payload)) {
            throw new IllegalArgumentException("Instant research task has an invalid payload");
        }
        var workflowCommand = new StartWorkflowCommand(
                payload.workflowType(),
                payload.trigger(),
                payload.workflowVersionId(),
                payload.question(),
                payload.workflowIdempotencyKey());
                return researchPipeline.execute(new ResearchPipelineRequest(
                        workflowCommand,
                        payload.taskMode().forAttempt(task.attemptCount()),
                        task.attemptCount(),
                        task.maximumAttempts(),
                        payload.marketAnalysisScope()))
                .thenApply(ignored -> null);
    }
}
