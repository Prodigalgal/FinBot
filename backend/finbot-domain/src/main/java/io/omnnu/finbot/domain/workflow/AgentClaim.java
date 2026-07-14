package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.shared.DomainText;
import java.util.List;

public record AgentClaim(String statement, List<String> evidenceReferences) {
    public AgentClaim {
        statement = DomainText.required(statement, "statement", 4_000);
        evidenceReferences = List.copyOf(evidenceReferences);
        if (evidenceReferences.size() > 32
                || evidenceReferences.stream().anyMatch(reference -> reference == null || reference.isBlank())) {
            throw new IllegalArgumentException("Invalid claim evidence references");
        }
    }
}
