package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.shared.DomainText;

public record WorkflowRunId(String value) {
    public WorkflowRunId {
        value = DomainText.identifier(value, "run_");
    }
}
