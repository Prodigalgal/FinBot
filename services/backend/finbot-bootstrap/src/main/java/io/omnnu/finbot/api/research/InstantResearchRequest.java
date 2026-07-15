package io.omnnu.finbot.api.research;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InstantResearchRequest(
        @NotBlank @Size(max = 2_000) String question,
        @Size(max = 80) String workflowVersionId,
        @Size(max = 80) String demoWorkflowVersionId) {
}
