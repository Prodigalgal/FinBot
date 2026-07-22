package io.omnnu.finbot.application.ai.port.out;

import io.omnnu.finbot.application.ai.dto.AiInvocationCompletion;
import io.omnnu.finbot.application.ai.dto.AiInvocationFailure;
import io.omnnu.finbot.application.ai.dto.AiInvocationStart;

import io.omnnu.finbot.domain.ai.AiInvocationId;
import java.time.Instant;

public interface AiInvocationAuditStore {
    void start(AiInvocationStart start);

    void appendChunk(AiInvocationId invocationId, long sequence, String content, Instant occurredAt);

    void complete(AiInvocationCompletion completion);

    void fail(AiInvocationFailure failure);
}
