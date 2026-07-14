package io.omnnu.finbot.application.research;

import io.omnnu.finbot.application.workflow.StartWorkflowCommand;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ResearchLaunchUseCase {
    CompletionStage<ResearchLaunchResult> launch(
            StartWorkflowCommand workflowCommand,
            String taskIdempotencyKey);
}
