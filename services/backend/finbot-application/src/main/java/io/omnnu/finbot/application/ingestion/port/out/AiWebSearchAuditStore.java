package io.omnnu.finbot.application.ingestion.port.out;

import io.omnnu.finbot.domain.ingestion.AiWebSearchBinding;
import io.omnnu.finbot.domain.ingestion.SourceId;
import java.time.Instant;

public interface AiWebSearchAuditStore {
    void start(
            String invocationId,
            SourceId sourceId,
            AiWebSearchBinding binding,
            String queryHash,
            Instant startedAt);

    void complete(
            String invocationId,
            String providerRequestId,
            long inputTokens,
            long outputTokens,
            int citationCount,
            Instant completedAt);

    void fail(
            String invocationId,
            String errorCode,
            String errorMessage,
            Instant completedAt);
}
