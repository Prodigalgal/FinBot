package io.omnnu.finbot.application.research;

import io.omnnu.finbot.domain.catalog.InstrumentId;

public record ForecastEvaluationCandidate(
        String forecastId,
        InstrumentId instrumentId,
        int intervalSeconds) {
}
