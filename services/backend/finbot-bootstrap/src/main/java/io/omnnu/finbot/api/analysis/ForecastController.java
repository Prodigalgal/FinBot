package io.omnnu.finbot.api.analysis;

import io.omnnu.finbot.application.research.ResearchForecastUseCase;
import io.omnnu.finbot.application.research.ResearchForecastView;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v2/analysis")
public final class ForecastController {
    private final ResearchForecastUseCase forecasts;

    public ForecastController(ResearchForecastUseCase forecasts) {
        this.forecasts = Objects.requireNonNull(forecasts, "forecasts");
    }

    @GetMapping("/market-runs/{runId}/forecast")
    public ForecastResponse forecast(@PathVariable String runId) {
        return forecasts.findByRun(new WorkflowRunId(runId))
                .map(ForecastResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Research forecast not found"));
    }

    @GetMapping("/forecasts")
    public List<ForecastResponse> forecasts(
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return forecasts.recent(limit).stream().map(ForecastResponse::from).toList();
    }

    public record ForecastResponse(
            String forecastId,
            String workflowRunId,
            String instrumentId,
            String exchange,
            String symbol,
            int intervalSeconds,
            int horizonSeconds,
            BigDecimal marketReferencePrice,
            String direction,
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
        static ForecastResponse from(ResearchForecastView value) {
            return new ForecastResponse(
                    value.forecastId(),
                    value.workflowRunId().value(),
                    value.instrumentId().value(),
                    value.exchange().name(),
                    value.symbol(),
                    value.intervalSeconds(),
                    value.horizonSeconds(),
                    value.marketReferencePrice(),
                    value.direction().name(),
                    value.expectedLow(),
                    value.expectedHigh(),
                    value.invalidationPrice(),
                    value.confidence(),
                    value.thesis(),
                    value.evidenceReferences(),
                    value.status(),
                    value.issuedAt(),
                    value.targetAt(),
                    value.actualPrice(),
                    value.actualReturn(),
                    value.directionCorrect(),
                    value.rangeHit(),
                    value.evaluatedAt());
        }
    }
}
