package io.omnnu.finbot.application.workflow;

import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import java.util.concurrent.CompletionStage;

public interface WorkflowDiagnosticsUseCase {
    WorkflowEstimate estimate(WorkflowVersionId versionId);

    WorkflowExecutionPlan plan(WorkflowVersionId versionId);

    CompletionStage<WorkflowNodeTestResult> testNode(
            WorkflowVersionId versionId,
            WorkflowNodeId nodeId,
            String userPrompt,
            String idempotencyKey);
}
