package io.omnnu.finbot.application.ingestion;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record AiWebSearchResult(
        String answer,
        List<AiWebSearchCitation> citations,
        long inputTokens,
        long outputTokens,
        String providerRequestId,
        Instant completedAt) {
    public AiWebSearchResult {
        answer = Objects.requireNonNull(answer, "answer").strip();
        citations = List.copyOf(citations);
        providerRequestId = providerRequestId == null || providerRequestId.isBlank()
                ? null
                : providerRequestId.strip();
        Objects.requireNonNull(completedAt, "completedAt");
        if (answer.isEmpty()) {
            throw new IllegalArgumentException("AI web search answer must not be empty");
        }
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("AI web search token usage must not be negative");
        }
    }
}
