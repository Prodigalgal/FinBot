package io.omnnu.finbot.application.market;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.research.ResearchDataPlane;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface MarketDataUseCase {
    CompletionStage<MarketDataPreparationResult> prepare(WorkflowRunId workflowRunId);

    default CompletionStage<MarketDataPreparationResult> prepare(
            WorkflowRunId workflowRunId,
            MarketAnalysisScope scope) {
        return prepare(workflowRunId);
    }

    default CompletionStage<MarketDataPreparationResult> prepare(
            WorkflowRunId workflowRunId,
            ResearchDataPlane dataPlane) {
        if (dataPlane != ResearchDataPlane.LIVE) {
            return java.util.concurrent.CompletableFuture.failedStage(
                    new UnsupportedOperationException("Paper market data is not supported by this implementation"));
        }
        return prepare(workflowRunId);
    }
}
