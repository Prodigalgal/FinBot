package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.shared.DomainText;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record AgentMessage(
        AgentMessageId messageId,
        DebateId debateId,
        WorkflowRunId runId,
        WorkflowNodeId nodeId,
        String roleName,
        int roundIndex,
        int turnIndex,
        AgentMessageType messageType,
        AgentMessageStatus status,
        AgentMessageContent content,
        List<AgentMessageId> repliesTo,
        Instant createdAt) {
    public AgentMessage {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(debateId, "debateId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(nodeId, "nodeId");
        roleName = DomainText.required(roleName, "roleName", 120);
        if (roundIndex < 0 || roundIndex > 8 || turnIndex < 0) {
            throw new IllegalArgumentException("Invalid agent message round or turn");
        }
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(content, "content");
        repliesTo = List.copyOf(repliesTo);
        if (repliesTo.contains(messageId)) {
            throw new IllegalArgumentException("Agent message cannot reply to itself");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
