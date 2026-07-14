package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.shared.DomainText;

public record AgentRoleTemplateId(String value) {
    public AgentRoleTemplateId {
        value = DomainText.identifier(value, "role_");
    }
}
