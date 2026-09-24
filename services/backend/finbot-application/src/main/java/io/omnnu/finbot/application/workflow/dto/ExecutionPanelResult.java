package io.omnnu.finbot.application.workflow.dto;

import io.omnnu.finbot.application.trading.dto.TradeDecisionDraft;
import io.omnnu.finbot.domain.consensus.ConsensusDecision;
import io.omnnu.finbot.domain.workflow.AgentMessage;
import java.util.Objects;

public record ExecutionPanelResult(
        DebateSession session,
        AgentMessage message,
        ConsensusDecision consensusDecision,
        TradeDecisionDraft decisionDraft,
        boolean approved,
        boolean partial) {
    public ExecutionPanelResult {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(consensusDecision, "consensusDecision");
    }
}
