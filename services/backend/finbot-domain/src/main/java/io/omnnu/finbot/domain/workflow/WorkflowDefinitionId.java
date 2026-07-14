package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.shared.DomainText;

public record WorkflowDefinitionId(String value) {
    public WorkflowDefinitionId {
        value = DomainText.identifier(value, "workflow_");
    }
}
