package io.omnnu.finbot.application.workflow;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.concurrent.CompletionStage;

public interface WorkflowExecutionUseCase {
    CompletionStage<Void> execute(WorkflowRunId runId);
}
