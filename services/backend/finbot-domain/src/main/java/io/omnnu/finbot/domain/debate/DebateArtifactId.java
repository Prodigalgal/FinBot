package io.omnnu.finbot.domain.debate;

import io.omnnu.finbot.domain.shared.DomainText;

public record DebateArtifactId(String value) {
    public DebateArtifactId {
        value = DomainText.identifier(value, "debate_artifact_");
    }
}
