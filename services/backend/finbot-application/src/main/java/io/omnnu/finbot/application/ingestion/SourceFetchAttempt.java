package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.ingestion.CollectionRunId;
import io.omnnu.finbot.domain.ingestion.SourceId;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;

public record SourceFetchAttempt(
        String attemptId,
        CollectionRunId collectionId,
        SourceId sourceId,
        URI requestedUrl,
        String routeType,
        Integer statusCode,
        String contentType,
        int responseBytes,
        int retryCount,
        String outcome,
        String errorCode,
        String parserVersion,
        Instant startedAt,
        Instant completedAt) {
    public SourceFetchAttempt {
        attemptId = requireText(attemptId, "attemptId", 80);
        collectionId = Objects.requireNonNull(collectionId, "collectionId");
        sourceId = Objects.requireNonNull(sourceId, "sourceId");
        routeType = requireText(routeType, "routeType", 32);
        contentType = optional(contentType, 160);
        outcome = requireText(outcome, "outcome", 24);
        errorCode = optional(errorCode, 80);
        parserVersion = optional(parserVersion, 120);
        if (requestedUrl != null && (requestedUrl.getHost() == null
                || requestedUrl.getUserInfo() != null || requestedUrl.getFragment() != null)) {
            throw new IllegalArgumentException("requestedUrl must be a safe URL summary");
        }
        if (statusCode != null && (statusCode < 100 || statusCode > 599)) {
            throw new IllegalArgumentException("statusCode is invalid");
        }
        if (responseBytes < 0 || retryCount < 0) {
            throw new IllegalArgumentException("fetch attempt counters are invalid");
        }
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not precede startedAt");
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
