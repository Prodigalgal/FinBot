package io.omnnu.finbot.domain.debate;

import io.omnnu.finbot.domain.shared.DomainText;

public record DebateTaskId(String value) {
    public DebateTaskId {
        value = DomainText.identifier(value, "debate_task_");
    }
}
