package io.omnnu.finbot.domain.ingestion;

import io.omnnu.finbot.domain.shared.DomainText;

public record SourceId(String value) {
    public SourceId {
        value = DomainText.identifier(value, "source_");
    }
}
