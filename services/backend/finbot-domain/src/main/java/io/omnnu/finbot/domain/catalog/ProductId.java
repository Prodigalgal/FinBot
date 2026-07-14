package io.omnnu.finbot.domain.catalog;

import io.omnnu.finbot.domain.shared.DomainText;

public record ProductId(String value) {
    public ProductId {
        value = DomainText.identifier(value, "product_");
    }
}
