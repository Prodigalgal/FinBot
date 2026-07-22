package io.omnnu.finbot.application.workflow.dto;

import io.omnnu.finbot.domain.workflow.WorkflowDefinitionId;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import java.time.Instant;

public record WorkflowDefinitionSummary(
        WorkflowDefinitionId definitionId,
        String name,
        String description,
        boolean builtIn,
        boolean active,
        WorkflowVersionId publishedVersionId,
        Integer publishedVersionNumber,
        WorkflowVersionId draftVersionId,
        Integer draftVersionNumber,
        Instant updatedAt) {
}
