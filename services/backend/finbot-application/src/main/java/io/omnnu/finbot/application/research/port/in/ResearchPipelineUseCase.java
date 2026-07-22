package io.omnnu.finbot.application.research.port.in;

import io.omnnu.finbot.application.research.dto.ResearchPipelineRequest;

import io.omnnu.finbot.application.workflow.dto.StartWorkflowResult;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ResearchPipelineUseCase {
    CompletionStage<StartWorkflowResult> execute(ResearchPipelineRequest request);
}
