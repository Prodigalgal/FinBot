package io.omnnu.finbot.application.trading.port.in;

import io.omnnu.finbot.application.trading.dto.TradeAutomationResult;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface TradeAutomationUseCase {
    CompletionStage<TradeAutomationResult> execute(WorkflowRunId workflowRunId);
}
