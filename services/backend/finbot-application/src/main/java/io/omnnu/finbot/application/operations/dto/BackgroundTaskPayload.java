package io.omnnu.finbot.application.operations.dto;

public sealed interface BackgroundTaskPayload permits
        ScheduledResearchTaskPayload,
        InstantResearchTaskPayload,
        AccountTaskPayload,
        MarketDataTaskPayload,
        IngestionTaskPayload,
        CatalogSyncTaskPayload,
        ForecastEvaluationTaskPayload {
}
