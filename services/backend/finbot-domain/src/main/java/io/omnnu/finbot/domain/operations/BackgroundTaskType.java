package io.omnnu.finbot.domain.operations;

public enum BackgroundTaskType {
    SCHEDULED_RESEARCH,
    INSTANT_RESEARCH,
    ACCOUNT_SYNC,
    ORDER_RECONCILIATION,
    MARKET_DATA_SYNC,
    INGESTION,
    CATALOG_SYNC,
    FORECAST_EVALUATION
}
