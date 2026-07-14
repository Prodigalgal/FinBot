package io.omnnu.finbot.application.ingestion;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record CollectedPayload(
        URI requestedUrl,
        URI canonicalUrl,
        String query,
        String title,
        int statusCode,
        String contentType,
        String rawContent,
        Map<String, String> responseHeaders,
        Map<String, String> metadata,
        Instant publishedAt,
        Instant fetchedAt) {
    public CollectedPayload {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode is invalid");
        }
        contentType = Objects.requireNonNullElse(contentType, "application/octet-stream").strip();
        rawContent = Objects.requireNonNull(rawContent, "rawContent");
        responseHeaders = Map.copyOf(responseHeaders);
        metadata = Map.copyOf(metadata);
        Objects.requireNonNull(fetchedAt, "fetchedAt");
    }
}
