package io.omnnu.finbot.application.workflow.port.in;

import io.omnnu.finbot.application.workflow.dto.PrincipalReviewResult;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;

public interface PrincipalReviewUseCase {
    PrincipalReviewResult run(WorkflowExecutionContext execution);
}
