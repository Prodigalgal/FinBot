package io.omnnu.finbot.application.market;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface MarketDataUseCase {
    CompletionStage<MarketDataPreparationResult> prepare(WorkflowRunId workflowRunId);
}
