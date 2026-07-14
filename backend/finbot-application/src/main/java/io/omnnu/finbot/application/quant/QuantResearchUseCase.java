package io.omnnu.finbot.application.quant;

import io.omnnu.finbot.application.market.MarketDataPreparationResult;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface QuantResearchUseCase {
    CompletionStage<QuantResearchExecutionResult> execute(
            WorkflowRunId workflowRunId,
            MarketDataPreparationResult marketData);
}
