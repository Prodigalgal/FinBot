package io.omnnu.finbot.domain.ingestion;

import io.omnnu.finbot.domain.shared.DomainText;

public record DocumentId(String value) {
    public DocumentId {
        value = DomainText.identifier(value, "document_");
    }
}
