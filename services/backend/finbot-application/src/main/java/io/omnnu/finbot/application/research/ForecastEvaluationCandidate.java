package io.omnnu.finbot.application.research;

import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;

public record ForecastEvaluationCandidate(
        String forecastId,
        InstrumentId instrumentId,
        ExchangeEnvironment environment,
        int intervalSeconds) {
}
