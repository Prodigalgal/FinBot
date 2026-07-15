package io.omnnu.finbot.application.operations;

public record ForecastEvaluationTaskPayload(int limit) implements BackgroundTaskPayload {
    public ForecastEvaluationTaskPayload {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("forecast evaluation limit must be between 1 and 200");
        }
    }
}
