package io.omnnu.finbot.application.research;

import io.omnnu.finbot.application.workflow.StartWorkflowResult;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ResearchPipelineUseCase {
    CompletionStage<StartWorkflowResult> execute(ResearchPipelineRequest request);
}
