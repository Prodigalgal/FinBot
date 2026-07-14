package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.ai.AiInvocationId;
import java.time.Instant;
import java.util.Objects;

public record AiTextChunkPublished(
        WorkflowEventId eventId,
        WorkflowRunId runId,
        long sequence,
        AiInvocationId invocationId,
        WorkflowNodeId nodeId,
        long chunkSequence,
        String text,
        Instant occurredAt) implements WorkflowEvent {
    public AiTextChunkPublished {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(runId, "runId");
        if (sequence < 1 || chunkSequence < 1) {
            throw new IllegalArgumentException("Event and chunk sequences must be positive");
        }
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(nodeId, "nodeId");
        text = Objects.requireNonNull(text, "text");
        if (text.isEmpty() || text.length() > 8_192) {
            throw new IllegalArgumentException("AI text chunk must contain 1 to 8192 characters");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
