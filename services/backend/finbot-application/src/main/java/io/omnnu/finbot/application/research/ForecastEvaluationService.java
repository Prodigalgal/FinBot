package io.omnnu.finbot.application.research;

import io.omnnu.finbot.application.market.MarketDataRefreshUseCase;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class ForecastEvaluationService implements ForecastEvaluationUseCase {
    private final ResearchForecastRepository repository;
    private final MarketDataRefreshUseCase marketData;
    private final Clock clock;

    public ForecastEvaluationService(
            ResearchForecastRepository repository,
            MarketDataRefreshUseCase marketData,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.marketData = Objects.requireNonNull(marketData, "marketData");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<Integer> evaluateDue(int limit) {
        var candidates = repository.due(clock.instant(), Math.max(1, Math.min(limit, 200)));
        var evaluations = candidates.stream()
                .map(candidate -> marketData.refresh(
                                candidate.instrumentId(), candidate.intervalSeconds(), candidate.environment())
                        .handle((ignored, failure) -> repository.evaluate(candidate.forecastId(), clock.instant()) ? 1 : 0)
                        .toCompletableFuture())
                .toList();
        return CompletableFuture.allOf(evaluations.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> evaluations.stream().mapToInt(CompletableFuture::join).sum());
    }
}
