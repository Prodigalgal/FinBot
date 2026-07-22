package io.omnnu.finbot.application.research.port.in;

import io.omnnu.finbot.application.research.dto.CompressionBatchResult;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface CompressionUseCase {
    CompletionStage<CompressionBatchResult> compress(WorkflowRunId workflowRunId);
}
