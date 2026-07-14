package io.omnnu.finbot.api.workflow;

import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StartWorkflowRequest(
        @NotNull WorkflowType workflowType,
        @NotNull WorkflowTrigger trigger,
        @Size(max = 80) String workflowVersionId,
        @NotBlank @Size(max = 2_000) String requestSummary) {
}
