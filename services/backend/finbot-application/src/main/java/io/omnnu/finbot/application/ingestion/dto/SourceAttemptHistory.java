package io.omnnu.finbot.application.ingestion.dto;

import java.time.Instant;

public record SourceAttemptHistory(
        Instant lastSuccessAt,
        Instant lastBlockedAt,
        Instant lastAttemptAt,
        String latestOutcome,
        Integer latestStatusCode,
        String latestErrorCode,
        String safeMessage) {
}
