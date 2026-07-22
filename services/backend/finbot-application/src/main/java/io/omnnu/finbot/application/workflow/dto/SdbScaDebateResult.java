package io.omnnu.finbot.application.workflow.dto;

import io.omnnu.finbot.domain.workflow.AgentMessage;
import java.util.Objects;

public record SdbScaDebateResult(
        DebateSession session,
        AgentMessage consensusMessage,
        boolean partial) {
    public SdbScaDebateResult {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(consensusMessage, "consensusMessage");
    }
}
