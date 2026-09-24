package io.omnnu.finbot.application.workflow.port.in;

import io.omnnu.finbot.application.workflow.dto.ExecutionPanelResult;
import io.omnnu.finbot.application.workflow.dto.PrincipalReviewResult;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.concurrent.CompletionStage;

public interface ExecutionPanelUseCase {
    CompletionStage<ExecutionPanelResult> execute(
            WorkflowRunId workflowRunId,
            PrincipalReviewResult principalReview);

    ExecutionPanelResult run(
            WorkflowExecutionContext execution,
            PrincipalReviewResult principalReview);
}
