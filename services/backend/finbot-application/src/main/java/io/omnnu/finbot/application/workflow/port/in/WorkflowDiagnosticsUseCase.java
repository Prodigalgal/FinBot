package io.omnnu.finbot.application.workflow.port.in;

import io.omnnu.finbot.application.workflow.dto.WorkflowEstimate;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionPlan;
import io.omnnu.finbot.application.workflow.dto.WorkflowNodeTestResult;

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
