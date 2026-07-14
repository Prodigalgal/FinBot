package io.omnnu.finbot.application.trading;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface TradeAutomationUseCase {
    CompletionStage<TradeAutomationResult> execute(WorkflowRunId workflowRunId);
}
