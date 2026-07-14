package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.shared.DomainText;

public record WorkflowEventId(String value) {
    public WorkflowEventId {
        value = DomainText.identifier(value, "event_");
    }
}
