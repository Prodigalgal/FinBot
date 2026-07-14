package io.omnnu.finbot.application.research;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface CompressionUseCase {
    CompletionStage<CompressionBatchResult> compress(WorkflowRunId workflowRunId);
}
