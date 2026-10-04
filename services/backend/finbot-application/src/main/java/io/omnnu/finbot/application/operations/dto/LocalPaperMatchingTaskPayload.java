package io.omnnu.finbot.application.operations.dto;

public record LocalPaperMatchingTaskPayload(int limit) implements BackgroundTaskPayload {
    public LocalPaperMatchingTaskPayload {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("paper matching limit must be between 1 and 100");
    }
}
