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
        Instant fetchedAt,
        ContentEnvelope envelope) {
    public CollectedPayload(
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
        this(
                requestedUrl,
                canonicalUrl,
                query,
                title,
                statusCode,
                contentType,
                rawContent,
                responseHeaders,
                metadata,
                publishedAt,
                fetchedAt,
                ContentEnvelope.raw(requestedUrl, canonicalUrl, contentType, rawContent));
    }

    public CollectedPayload {
        requestedUrl = Objects.requireNonNull(requestedUrl, "requestedUrl");
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode is invalid");
        }
        contentType = Objects.requireNonNullElse(contentType, "application/octet-stream").strip();
        rawContent = Objects.requireNonNull(rawContent, "rawContent");
        responseHeaders = Map.copyOf(responseHeaders);
        metadata = Map.copyOf(metadata);
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        envelope = Objects.requireNonNull(envelope, "envelope");
    }

    public Map<String, String> evidenceMetadata() {
        var values = new java.util.HashMap<>(metadata);
        values.put("content_envelope_schema", Integer.toString(envelope.schemaVersion()));
        values.put("content_block_ids", String.join(",", envelope.blockIds()));
        values.put("content_block_count", Integer.toString(envelope.blocks().size()));
        return Map.copyOf(values);
    }

    public CollectedPayload withEnvelope(ContentEnvelope value) {
        return new CollectedPayload(
                requestedUrl,
                canonicalUrl,
                query,
                title,
                statusCode,
                contentType,
                rawContent,
                responseHeaders,
                metadata,
                publishedAt,
                fetchedAt,
                value);
    }

    public CollectedPayload withAdditionalMetadata(Map<String, String> values) {
        var combined = new java.util.HashMap<>(metadata);
        combined.putAll(Objects.requireNonNull(values, "values"));
        return new CollectedPayload(
                requestedUrl,
                canonicalUrl,
                query,
                title,
                statusCode,
                contentType,
                rawContent,
                responseHeaders,
                Map.copyOf(combined),
                publishedAt,
                fetchedAt,
                envelope);
    }
}
