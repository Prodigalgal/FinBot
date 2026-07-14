package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.shared.DomainText;
import java.time.Instant;
import java.util.Objects;

public record AgentMessagePublished(
        WorkflowEventId eventId,
        WorkflowRunId runId,
        long sequence,
        WorkflowNodeId nodeId,
        String roleId,
        int round,
        String summary,
        Instant occurredAt) implements WorkflowEvent {

    public AgentMessagePublished {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(runId, "runId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        Objects.requireNonNull(nodeId, "nodeId");
        roleId = DomainText.required(roleId, "roleId", 120);
        if (round < 1) {
            throw new IllegalArgumentException("round must be positive");
        }
        summary = DomainText.required(summary, "summary", 8_000);
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
