package io.omnnu.finbot.application.research;

import io.omnnu.finbot.application.market.MarketAnalysisScope;
import io.omnnu.finbot.application.operations.ResearchTaskMode;
import io.omnnu.finbot.application.workflow.StartWorkflowCommand;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ResearchLaunchUseCase {
    CompletionStage<ResearchLaunchResult> launch(
            StartWorkflowCommand workflowCommand,
            String taskIdempotencyKey,
            ResearchTaskMode taskMode);

    default CompletionStage<ResearchLaunchResult> launch(
            StartWorkflowCommand workflowCommand,
            String taskIdempotencyKey,
            ResearchTaskMode taskMode,
            MarketAnalysisScope marketAnalysisScope) {
        if (marketAnalysisScope != null) {
            throw new UnsupportedOperationException("Market analysis scope is not supported by this launcher");
        }
        return launch(workflowCommand, taskIdempotencyKey, taskMode);
    }
}
