package io.omnnu.finbot.domain.ingestion;

import io.omnnu.finbot.domain.shared.DomainText;

public record EvidenceId(String value) {
    public EvidenceId {
        value = DomainText.identifier(value, "evidence_");
    }
}
