package io.omnnu.finbot.domain.research;

import io.omnnu.finbot.domain.shared.DomainText;

public record ResearchArtifactId(String value) {
    public ResearchArtifactId {
        value = DomainText.identifier(value, "artifact_");
    }
}
