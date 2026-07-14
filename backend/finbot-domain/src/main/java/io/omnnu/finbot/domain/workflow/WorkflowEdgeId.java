package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.shared.DomainText;

public record WorkflowEdgeId(String value) {
    public WorkflowEdgeId {
        value = DomainText.identifier(value, "edge_");
    }
}
