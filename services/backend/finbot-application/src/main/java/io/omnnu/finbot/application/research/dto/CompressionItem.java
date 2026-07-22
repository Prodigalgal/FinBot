package io.omnnu.finbot.application.research.dto;

import io.omnnu.finbot.domain.ingestion.DocumentId;
import io.omnnu.finbot.domain.ingestion.EvidenceId;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.research.CompressionId;

public record CompressionItem(
        CompressionId compressionId,
        DocumentId documentId,
        EvidenceId evidenceId,
        SourceId sourceId,
        CompressionStatus status,
        CompressionContent content,
        String errorCode) {
}
