package io.omnnu.finbot.api.ingestion.dto;

import io.omnnu.finbot.application.ingestion.dto.NormalizedDocument;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record DocumentResponse(
        String documentId,
        String evidenceId,
        String sourceId,
        String sourceTier,
        String category,
        BigDecimal trustWeight,
        String canonicalUrl,
        String title,
        String language,
        String excerpt,
        List<String> assetScope,
        Instant publishedAt,
        Instant fetchedAt) {
    public static DocumentResponse from(NormalizedDocument document) {
        var text = document.normalizedText();
        return new DocumentResponse(
                document.documentId().value(),
                document.evidenceId().value(),
                document.sourceId().value(),
                document.sourceTier().name(),
                document.category(),
                document.trustWeight(),
                document.canonicalUrl() == null ? null : document.canonicalUrl().toString(),
                document.title(),
                document.language(),
                text.substring(0, Math.min(text.length(), 2_000)),
                document.assetScope(),
                document.publishedAt(),
                document.fetchedAt());
    }
}
