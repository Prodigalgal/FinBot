package io.omnnu.finbot.domain.operations;

import io.omnnu.finbot.domain.shared.DomainText;

public record BackgroundTaskId(String value) {
    public BackgroundTaskId {
        value = DomainText.identifier(value, "task_");
    }
}
