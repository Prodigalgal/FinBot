package io.omnnu.finbot.api.ingestion.dto;

import io.omnnu.finbot.application.ingestion.dto.SourceRuntimeHealth;
import java.time.Instant;

public record SourceHealthResponse(
        String sourceId,
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
    public static SourceHealthResponse from(SourceRuntimeHealth health) {
        return new SourceHealthResponse(
                health.sourceId().value(),
                health.serviceReady(),
                health.egressReady(),
                health.routeType(),
                health.routeEndpoint(),
                health.channelStatus(),
                health.firecrawlChannelStatus(),
                health.rateLimitStatus(),
                health.lastSuccessAt(),
                health.lastBlockedAt(),
                health.lastAttemptAt(),
                health.latestOutcome(),
                health.latestStatusCode(),
                health.latestErrorCode(),
                health.safeMessage());
    }
}
