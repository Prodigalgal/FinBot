package io.omnnu.finbot.domain.quant;

import io.omnnu.finbot.domain.shared.DomainText;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;
import java.util.Objects;

public record QuantResearchRequest(
        ResearchRunId researchRunId,
        WorkflowRunId workflowRunId,
        String idempotencyKey,
        QuantResearchSpecification specification,
        Instant requestedAt) {

    public QuantResearchRequest {
        Objects.requireNonNull(researchRunId, "researchRunId");
        Objects.requireNonNull(workflowRunId, "workflowRunId");
        idempotencyKey = DomainText.required(idempotencyKey, "idempotencyKey", 120);
        if (idempotencyKey.length() < 8) {
            throw new IllegalArgumentException("idempotencyKey must contain at least 8 characters");
        }
        Objects.requireNonNull(specification, "specification");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
