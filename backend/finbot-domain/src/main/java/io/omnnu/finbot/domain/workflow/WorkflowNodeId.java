package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.shared.DomainText;

public record WorkflowNodeId(String value) {
    public WorkflowNodeId {
        value = DomainText.identifier(value, "node_");
    }
}
