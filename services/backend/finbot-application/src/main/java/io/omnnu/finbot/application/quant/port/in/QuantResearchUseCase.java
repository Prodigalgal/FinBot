package io.omnnu.finbot.application.quant.port.in;

import io.omnnu.finbot.application.quant.dto.QuantResearchExecutionResult;

import io.omnnu.finbot.application.market.dto.MarketDataPreparationResult;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface QuantResearchUseCase {
    CompletionStage<QuantResearchExecutionResult> execute(
            WorkflowRunId workflowRunId,
            MarketDataPreparationResult marketData);
}
