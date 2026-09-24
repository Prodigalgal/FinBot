package io.omnnu.finbot.application.workflow.dto;

import io.omnnu.finbot.domain.consensus.ConsensusDecision;
import io.omnnu.finbot.domain.debate.PrincipalReviewDecision;
import io.omnnu.finbot.domain.workflow.AgentMessage;
import java.util.Objects;

public record PrincipalReviewResult(
        DebateSession session,
        AgentMessage message,
        ConsensusDecision consensusDecision,
        PrincipalReviewDecision reviewDecision,
        boolean approved,
        boolean partial) {
    public PrincipalReviewResult {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(consensusDecision, "consensusDecision");
    }
}
