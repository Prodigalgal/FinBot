package io.omnnu.finbot.application.research;

import io.omnnu.finbot.domain.ai.AiInvocationId;
import io.omnnu.finbot.domain.ingestion.DocumentId;
import io.omnnu.finbot.domain.research.CompressionId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;

public record AiCompressionRecord(
        CompressionId compressionId,
        WorkflowRunId workflowRunId,
        DocumentId documentId,
        AiInvocationId invocationId,
        CompressionStatus status,
        CompressionContent content,
        String promptHash,
        String errorCode,
        String errorMessage,
        Instant createdAt) {
}
