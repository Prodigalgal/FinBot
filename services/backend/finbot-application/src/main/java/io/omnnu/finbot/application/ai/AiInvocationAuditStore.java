package io.omnnu.finbot.application.ai;

import io.omnnu.finbot.domain.ai.AiInvocationId;
import java.time.Instant;

public interface AiInvocationAuditStore {
    void start(AiInvocationStart start);

    void appendChunk(AiInvocationId invocationId, long sequence, String content, Instant occurredAt);

    void complete(AiInvocationCompletion completion);

    void fail(AiInvocationFailure failure);
}
