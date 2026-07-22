package io.omnnu.finbot.application.research.dto;

import io.omnnu.finbot.domain.ai.AiInvocationId;
import io.omnnu.finbot.domain.ingestion.DocumentId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import java.time.Instant;
import java.util.Objects;

public record EvidenceAiReview(
        String reviewId,
        WorkflowRunId workflowRunId,
        WorkflowVersionId workflowVersionId,
        DocumentId documentId,
        WorkflowNodeId nodeId,
        AiInvocationId invocationId,
        EvidenceAiReviewStage stage,
        CompressionStatus status,
        CompressionContent content,
        String promptHash,
        String errorCode,
        String errorMessage,
        Instant createdAt) {

    public EvidenceAiReview {
        reviewId = Objects.requireNonNull(reviewId, "reviewId").strip();
        Objects.requireNonNull(workflowRunId, "workflowRunId");
        Objects.requireNonNull(workflowVersionId, "workflowVersionId");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(content, "content");
        promptHash = Objects.requireNonNull(promptHash, "promptHash").strip();
        Objects.requireNonNull(createdAt, "createdAt");
        if (!reviewId.matches("review_[a-z0-9_-]{8,73}")) {
            throw new IllegalArgumentException("Invalid evidence AI review id");
        }
        if (!promptHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid evidence AI review prompt hash");
        }
    }
}
