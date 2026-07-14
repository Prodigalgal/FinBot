package io.omnnu.finbot.domain.operations;

import io.omnnu.finbot.domain.shared.DomainText;

public record WorkerId(String value) {
    public WorkerId {
        value = DomainText.identifier(value, "worker_");
    }
}
