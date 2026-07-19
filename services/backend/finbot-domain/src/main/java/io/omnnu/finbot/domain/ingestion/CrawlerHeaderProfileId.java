package io.omnnu.finbot.domain.ingestion;

import io.omnnu.finbot.domain.shared.DomainText;

public record CrawlerHeaderProfileId(String value) {
    public CrawlerHeaderProfileId {
        value = DomainText.identifier(value, "header_");
    }
}
