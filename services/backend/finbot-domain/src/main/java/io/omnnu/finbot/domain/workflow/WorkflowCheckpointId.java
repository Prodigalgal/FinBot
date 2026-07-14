package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.shared.DomainText;

public record WorkflowCheckpointId(String value) {
    public WorkflowCheckpointId {
        value = DomainText.identifier(value, "checkpoint_");
    }
}
