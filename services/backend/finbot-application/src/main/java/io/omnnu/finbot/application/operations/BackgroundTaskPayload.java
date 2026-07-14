package io.omnnu.finbot.application.operations;

public sealed interface BackgroundTaskPayload permits
        ScheduledResearchTaskPayload,
        InstantResearchTaskPayload,
        AccountTaskPayload,
        MarketDataTaskPayload,
        IngestionTaskPayload {
}
