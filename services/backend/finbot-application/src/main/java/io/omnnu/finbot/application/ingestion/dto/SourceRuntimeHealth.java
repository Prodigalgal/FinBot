package io.omnnu.finbot.application.ingestion.dto;

import io.omnnu.finbot.domain.ingestion.SourceId;
import java.time.Instant;
import java.util.Objects;

/** Safe runtime channel state; it intentionally contains no credentials or proxy URLs. */
public record SourceRuntimeHealth(
        SourceId sourceId,
        boolean serviceReady,
        boolean egressReady,
        String routeType,
        String routeEndpoint,
        String channelStatus,
        String firecrawlChannelStatus,
        String rateLimitStatus,
        Instant lastSuccessAt,
        Instant lastBlockedAt,
        Instant lastAttemptAt,
        String latestOutcome,
        Integer latestStatusCode,
        String latestErrorCode,
        String safeMessage) {
    public SourceRuntimeHealth {
        sourceId = Objects.requireNonNull(sourceId, "sourceId");
        routeType = requireText(routeType, "routeType", 32);
        routeEndpoint = requireText(routeEndpoint, "routeEndpoint", 200);
        channelStatus = requireText(channelStatus, "channelStatus", 32);
        firecrawlChannelStatus = requireText(firecrawlChannelStatus, "firecrawlChannelStatus", 32);
        rateLimitStatus = requireText(rateLimitStatus, "rateLimitStatus", 32);
        latestOutcome = optional(latestOutcome, 24);
        latestErrorCode = optional(latestErrorCode, 80);
        safeMessage = optional(safeMessage, 2_000);
        if (latestStatusCode != null && (latestStatusCode < 100 || latestStatusCode > 599)) {
            throw new IllegalArgumentException("latestStatusCode is invalid");
        }
    }

    private static String requireText(String value, String field, int maximumLength) {
        var normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String optional(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireText(value, "optionalValue", maximumLength);
    }
}
