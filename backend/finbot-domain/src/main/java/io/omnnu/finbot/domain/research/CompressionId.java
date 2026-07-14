package io.omnnu.finbot.domain.research;

import io.omnnu.finbot.domain.shared.DomainText;

public record CompressionId(String value) {
    public CompressionId {
        value = DomainText.identifier(value, "compression_");
    }
}
