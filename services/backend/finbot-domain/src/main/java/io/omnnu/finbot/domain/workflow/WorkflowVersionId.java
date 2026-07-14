package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.shared.DomainText;

public record WorkflowVersionId(String value) {
    public WorkflowVersionId {
        value = DomainText.identifier(value, "workflowversion_");
    }
}
