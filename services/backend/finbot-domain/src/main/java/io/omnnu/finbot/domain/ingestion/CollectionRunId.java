package io.omnnu.finbot.domain.ingestion;

import io.omnnu.finbot.domain.shared.DomainText;

public record CollectionRunId(String value) {
    public CollectionRunId {
        value = DomainText.identifier(value, "collection_");
    }
}
