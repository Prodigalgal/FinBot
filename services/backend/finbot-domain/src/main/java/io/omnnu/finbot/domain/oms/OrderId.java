package io.omnnu.finbot.domain.oms;

import io.omnnu.finbot.domain.shared.DomainText;

public record OrderId(String value) {
    public OrderId {
        value = DomainText.identifier(value, "order_");
    }
}
