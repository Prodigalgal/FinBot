package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.shared.DomainText;

public record AgentMessageId(String value) {
    public AgentMessageId {
        value = DomainText.identifier(value, "message_");
    }
}
