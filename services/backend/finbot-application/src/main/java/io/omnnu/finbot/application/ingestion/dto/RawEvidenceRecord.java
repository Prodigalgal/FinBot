package io.omnnu.finbot.application.ingestion.dto;

import io.omnnu.finbot.domain.ingestion.CollectionRunId;
import io.omnnu.finbot.domain.ingestion.EvidenceId;
import io.omnnu.finbot.domain.ingestion.SourceId;
import java.net.URI;
import java.time.Instant;
import java.util.Map;

public record RawEvidenceRecord(
        EvidenceId evidenceId,
        CollectionRunId collectionId,
        SourceId sourceId,
        URI requestedUrl,
        URI canonicalUrl,
        String query,
        String title,
        int statusCode,
        String contentType,
        String rawContent,
        Map<String, String> responseHeaders,
        Map<String, String> metadata,
        String contentHash,
        String deduplicationKey,
        Instant publishedAt,
        Instant fetchedAt) {
    public RawEvidenceRecord {
        responseHeaders = Map.copyOf(responseHeaders);
        metadata = Map.copyOf(metadata);
    }
}
