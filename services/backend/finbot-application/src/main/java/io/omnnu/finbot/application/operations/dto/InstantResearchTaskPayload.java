package io.omnnu.finbot.application.operations.dto;

import io.omnnu.finbot.application.market.dto.MarketAnalysisScope;
import java.util.Objects;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;

public record InstantResearchTaskPayload(
        String requestId,
        String question,
        WorkflowType workflowType,
        WorkflowTrigger trigger,
        WorkflowVersionId workflowVersionId,
        WorkflowVersionId demoWorkflowVersionId,
        String workflowIdempotencyKey,
        ResearchTaskMode taskMode,
        MarketAnalysisScope marketAnalysisScope) implements BackgroundTaskPayload {
    public InstantResearchTaskPayload {
        requestId = requireText(requestId, "requestId", 80);
        question = requireText(question, "question", 2000);
        Objects.requireNonNull(workflowType, "workflowType");
        Objects.requireNonNull(trigger, "trigger");
        workflowIdempotencyKey = requireText(workflowIdempotencyKey, "workflowIdempotencyKey", 200);
        Objects.requireNonNull(taskMode, "taskMode");
    }

    private static String requireText(String value, String fieldName, int maximumLength) {
        var normalized = Objects.requireNonNull(value, fieldName).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
        return normalized;
    }
}
