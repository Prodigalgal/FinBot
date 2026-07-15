package io.omnnu.finbot.application.review;

import java.time.Instant;
import java.util.Objects;

public record ResearchFeedback(
        String feedbackId,
        String workflowRunId,
        ResearchFeedbackRating rating,
        ResearchEffectiveness effectiveness,
        String note,
        long version,
        Instant createdAt,
        Instant updatedAt) {
    public ResearchFeedback {
        feedbackId = Objects.requireNonNull(feedbackId, "feedbackId");
        workflowRunId = Objects.requireNonNull(workflowRunId, "workflowRunId");
        Objects.requireNonNull(rating, "rating");
        Objects.requireNonNull(effectiveness, "effectiveness");
        note = note == null ? "" : note.strip();
        if (note.length() > 2000) {
            throw new IllegalArgumentException("note must not exceed 2000 characters");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
