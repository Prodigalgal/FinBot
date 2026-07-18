package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.ingestion.DocumentId;
import io.omnnu.finbot.domain.ingestion.EvidenceId;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.ingestion.SourceTier;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;

public record NormalizedDocument(
        DocumentId documentId,
        EvidenceId evidenceId,
        SourceId sourceId,
        SourceTier sourceTier,
        String category,
        BigDecimal trustWeight,
        URI canonicalUrl,
        String title,
        String titleKey,
        String language,
        String normalizedText,
        List<ContentBlock> contentBlocks,
        String contentHash,
        List<String> assetScope,
        Instant publishedAt,
        Instant fetchedAt,
        Instant createdAt) {
    public NormalizedDocument {
        contentBlocks = List.copyOf(contentBlocks);
        assetScope = List.copyOf(assetScope);
    }
}
