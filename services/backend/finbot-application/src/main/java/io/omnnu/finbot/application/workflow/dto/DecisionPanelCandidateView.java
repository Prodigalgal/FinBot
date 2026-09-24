package io.omnnu.finbot.application.workflow.dto;

import io.omnnu.finbot.domain.consensus.AnonymousCandidateId;
import java.util.Objects;

public record DecisionPanelCandidateView(AnonymousCandidateId alias, String content) {
    public DecisionPanelCandidateView {
        Objects.requireNonNull(alias, "alias");
        content = Objects.requireNonNull(content, "content").strip();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("candidate content must not be blank");
        }
    }
}
