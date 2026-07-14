package io.omnnu.finbot.application.operations;

import java.util.Objects;

public record ScheduledResearchTaskPayload(String requestSummary) implements BackgroundTaskPayload {
    public ScheduledResearchTaskPayload {
        requestSummary = Objects.requireNonNull(requestSummary, "requestSummary").strip();
        if (requestSummary.isEmpty() || requestSummary.length() > 1000) {
            throw new IllegalArgumentException("requestSummary is invalid");
        }
    }
}
