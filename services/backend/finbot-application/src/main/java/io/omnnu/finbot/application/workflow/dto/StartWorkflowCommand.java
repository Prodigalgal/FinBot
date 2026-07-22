package io.omnnu.finbot.application.workflow.dto;

import io.omnnu.finbot.domain.shared.DomainText;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import java.util.Objects;

public record StartWorkflowCommand(
        WorkflowType workflowType,
        WorkflowTrigger trigger,
        WorkflowVersionId workflowVersionId,
        String requestSummary,
        String idempotencyKey) {

    public StartWorkflowCommand {
        Objects.requireNonNull(workflowType, "workflowType");
        Objects.requireNonNull(trigger, "trigger");
        requestSummary = DomainText.required(requestSummary, "requestSummary", 2_000);
        idempotencyKey = DomainText.required(idempotencyKey, "idempotencyKey", 200);
    }
}
