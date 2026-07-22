package io.omnnu.finbot.application.ingestion.dto;

import io.omnnu.finbot.domain.ingestion.DocumentId;
import io.omnnu.finbot.domain.ingestion.EvidenceId;
import io.omnnu.finbot.domain.ingestion.SourceId;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;

public record EvidenceExcerpt(
        DocumentId documentId,
        EvidenceId evidenceId,
        SourceId sourceId,
        String sourceTier,
        BigDecimal trustWeight,
        String category,
        String title,
        URI canonicalUrl,
        String excerpt,
        List<String> assetScope,
        Instant publishedAt,
        Instant fetchedAt) {
    public EvidenceExcerpt {
        assetScope = List.copyOf(assetScope);
    }

    public static EvidenceExcerpt from(NormalizedDocument document) {
        var text = document.normalizedText();
        var excerpt = text.substring(0, Math.min(text.length(), 2_000));
        return new EvidenceExcerpt(
                document.documentId(),
                document.evidenceId(),
                document.sourceId(),
                document.sourceTier().name(),
                document.trustWeight(),
                document.category(),
                document.title(),
                document.canonicalUrl(),
                excerpt,
                document.assetScope(),
                document.publishedAt(),
                document.fetchedAt());
    }
}
