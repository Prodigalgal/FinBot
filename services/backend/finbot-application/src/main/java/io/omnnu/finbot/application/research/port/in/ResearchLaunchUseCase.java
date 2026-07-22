package io.omnnu.finbot.application.research.port.in;

import io.omnnu.finbot.application.research.dto.ResearchLaunchResult;

import io.omnnu.finbot.application.market.dto.MarketAnalysisScope;
import io.omnnu.finbot.application.operations.dto.ResearchTaskMode;
import io.omnnu.finbot.application.workflow.dto.StartWorkflowCommand;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
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
        return launch(
                workflowCommand,
                taskIdempotencyKey,
                taskMode,
                marketAnalysisScope,
                null);
    }

    default CompletionStage<ResearchLaunchResult> launch(
            StartWorkflowCommand workflowCommand,
            String taskIdempotencyKey,
            ResearchTaskMode taskMode,
            MarketAnalysisScope marketAnalysisScope,
            WorkflowVersionId demoWorkflowVersionId) {
        if (marketAnalysisScope != null || demoWorkflowVersionId != null) {
            throw new UnsupportedOperationException(
                    "Market scope or demo workflow selection is not supported by this launcher");
        }
        return launch(workflowCommand, taskIdempotencyKey, taskMode);
    }
}
