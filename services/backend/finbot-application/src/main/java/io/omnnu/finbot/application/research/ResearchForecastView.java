package io.omnnu.finbot.application.research;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.research.ForecastDirection;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ResearchForecastView(
        String forecastId,
        WorkflowRunId workflowRunId,
        InstrumentId instrumentId,
        ExchangeVenue exchange,
        String symbol,
        int intervalSeconds,
        int horizonSeconds,
        BigDecimal marketReferencePrice,
        ForecastDirection direction,
        BigDecimal expectedLow,
        BigDecimal expectedHigh,
        BigDecimal invalidationPrice,
        BigDecimal confidence,
        String thesis,
        List<String> evidenceReferences,
        String status,
        Instant issuedAt,
        Instant targetAt,
        BigDecimal actualPrice,
        BigDecimal actualReturn,
        Boolean directionCorrect,
        Boolean rangeHit,
        Instant evaluatedAt) {
}
