package io.omnnu.finbot.application.market;

import io.omnnu.finbot.application.exchange.ExchangeCapabilityQuery;
import io.omnnu.finbot.domain.quant.ArtifactKind;
import io.omnnu.finbot.domain.quant.ResearchArtifact;
import io.omnnu.finbot.domain.research.ResearchArtifactId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.research.ResearchDataPlane;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public final class MarketDataApplicationService implements MarketDataUseCase, MarketDataRefreshUseCase {
    private static final int RESEARCH_INTERVAL_SECONDS = 3_600;
    private static final int RESEARCH_CANDLE_LIMIT = 500;

    private final MarketDataRepository repository;
    private final MarketDataGateway gateway;
    private final ExchangeCapabilityQuery capabilities;
    private final MarketDataArtifactEncoder artifactEncoder;
    private final MarketDataArtifactUriFactory artifactUriFactory;
    private final Clock clock;
    private final Executor executor;

    public MarketDataApplicationService(
            MarketDataRepository repository,
            MarketDataGateway gateway,
            ExchangeCapabilityQuery capabilities,
            MarketDataArtifactEncoder artifactEncoder,
            MarketDataArtifactUriFactory artifactUriFactory,
            Clock clock,
            Executor executor) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.artifactEncoder = Objects.requireNonNull(artifactEncoder, "artifactEncoder");
        this.artifactUriFactory = Objects.requireNonNull(artifactUriFactory, "artifactUriFactory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletionStage<MarketDataPreparationResult> prepare(WorkflowRunId workflowRunId) {
        Objects.requireNonNull(workflowRunId, "workflowRunId");
        return CompletableFuture.supplyAsync(
                () -> prepareSynchronously(workflowRunId, null, ResearchDataPlane.LIVE),
                executor);
    }

    @Override
    public CompletionStage<MarketDataPreparationResult> prepare(
            WorkflowRunId workflowRunId,
            MarketAnalysisScope scope) {
        Objects.requireNonNull(workflowRunId, "workflowRunId");
        Objects.requireNonNull(scope, "scope");
        var dataPlane = scope.environment() == ExchangeEnvironment.LIVE
                ? ResearchDataPlane.LIVE
                : ResearchDataPlane.PAPER;
        return CompletableFuture.supplyAsync(
                () -> prepareSynchronously(workflowRunId, scope, dataPlane),
                executor);
    }

    @Override
    public CompletionStage<MarketDataPreparationResult> prepare(
            WorkflowRunId workflowRunId,
            ResearchDataPlane dataPlane) {
        Objects.requireNonNull(workflowRunId, "workflowRunId");
        Objects.requireNonNull(dataPlane, "dataPlane");
        return CompletableFuture.supplyAsync(
                () -> prepareSynchronously(workflowRunId, null, dataPlane),
                executor);
    }

    @Override
    public CompletionStage<MarketDataRefreshResult> refresh(
            io.omnnu.finbot.domain.catalog.InstrumentId instrumentId) {
        return refresh(instrumentId, RESEARCH_INTERVAL_SECONDS);
    }

    @Override
    public CompletionStage<MarketDataRefreshResult> refresh(
            io.omnnu.finbot.domain.catalog.InstrumentId instrumentId,
            int intervalSeconds) {
        return refresh(instrumentId, intervalSeconds, ExchangeEnvironment.LIVE);
    }

    @Override
    public CompletionStage<MarketDataRefreshResult> refresh(
            io.omnnu.finbot.domain.catalog.InstrumentId instrumentId,
            int intervalSeconds,
            ExchangeEnvironment environment) {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(environment, "environment");
        return CompletableFuture.supplyAsync(
                () -> refreshSynchronously(instrumentId, intervalSeconds, environment),
                executor);
    }

    private MarketDataRefreshResult refreshSynchronously(
            io.omnnu.finbot.domain.catalog.InstrumentId instrumentId,
            int intervalSeconds,
            ExchangeEnvironment environment) {
        var instrument = repository.findInstrument(instrumentId)
                .orElseThrow(() -> new MarketDataFetchException(
                        "INSTRUMENT_NOT_FOUND",
                        "Active venue instrument was not found"));
        var outcome = fetch(instrument, environment, intervalSeconds);
        if (outcome.series() == null) {
            throw new MarketDataFetchException(
                    Objects.requireNonNullElse(outcome.source().errorCode(), "MARKET_DATA_FETCH_FAILED"),
                    Objects.requireNonNullElse(outcome.source().safeMessage(), "Market data refresh failed"));
        }
        repository.saveCandles(outcome.series().candles());
        return new MarketDataRefreshResult(
                instrumentId,
                outcome.series().candles().size(),
                clock.instant());
    }

    private MarketDataPreparationResult prepareSynchronously(
            WorkflowRunId workflowRunId,
            MarketAnalysisScope scope,
            ResearchDataPlane dataPlane) {
        var instruments = scope == null
                ? repository.listResearchInstruments()
                : repository.findInstrument(scope.instrumentId())
                        .filter(instrument -> instrument.exchange() == scope.exchange())
                        .filter(instrument -> instrument.symbol().equals(scope.symbol()))
                        .map(List::of)
                        .orElse(List.of());
        if (instruments.isEmpty()) {
            throw new MarketDataFetchException(
                    scope == null ? "RESEARCH_UNIVERSE_EMPTY" : "MARKET_ANALYSIS_INSTRUMENT_NOT_FOUND",
                    scope == null
                            ? "No active venue instruments are available in the default watchlist"
                            : "Requested live-market instrument is not present in the active product catalog");
        }
        var intervalSeconds = scope == null ? RESEARCH_INTERVAL_SECONDS : scope.intervalSeconds();
        var futures = instruments.stream()
                .map(instrument -> CompletableFuture.supplyAsync(
                        () -> fetch(
                                instrument,
                                scope == null
                                        ? environment(instrument.exchange(), dataPlane)
                                        : scope.environment(),
                                intervalSeconds),
                        executor))
                .toList();
        awaitAll(futures);
        var outcomes = futures.stream().map(CompletableFuture::join).toList();
        var series = outcomes.stream()
                .filter(outcome -> outcome.series() != null)
                .map(FetchOutcome::series)
                .toList();
        if (series.isEmpty()) {
            throw new MarketDataFetchException(
                    "ALL_MARKET_DATA_SOURCES_FAILED",
                    "No exchange returned usable public candle data");
        }
        repository.saveCandles(series.stream().flatMap(value -> value.candles().stream()).toList());
        if (scope != null) {
            var scopedSeries = series.getFirst();
            repository.saveResearchScope(
                    workflowRunId,
                    scopedSeries.instrument(),
                    scope,
                    scopedSeries.candles().getLast().close(),
                    clock.instant());
        }
        var encoded = artifactEncoder.encode(series);
        var artifactIdentity = sha256(workflowRunId.value() + ':' + dataPlane + ':' + encoded.sha256Hex());
        var artifactId = new ResearchArtifactId("artifact_" + artifactIdentity.substring(0, 40));
        repository.saveArtifact(new MarketDataArtifactRecord(
                artifactId,
                workflowRunId,
                dataPlane,
                2,
                encoded,
                clock.instant()));
        var artifact = new ResearchArtifact(
                ArtifactKind.INPUT_MARKET_DATA,
                artifactUriFactory.uri(artifactId),
                encoded.sha256Hex(),
                encoded.mediaType(),
                encoded.payload().length);
        return new MarketDataPreparationResult(
                artifactId,
                artifact,
                dataPlane,
                series.stream()
                        .map(value -> new MarketInstrumentBinding(
                                value.instrument(),
                                value.candles().getFirst().environment()))
                        .toList(),
                outcomes.stream().map(FetchOutcome::source).toList());
    }

    private FetchOutcome fetch(
            ResearchInstrument instrument,
            ExchangeEnvironment environment,
            int intervalSeconds) {
        try {
            capabilities.requireMarketData(
                    instrument.exchange(), instrument.marketType(), environment);
            var candles = gateway.fetchCandles(
                    instrument,
                    environment,
                    intervalSeconds,
                    RESEARCH_CANDLE_LIMIT);
            if (candles.size() < 50) {
                return failure(
                        instrument,
                        environment,
                        "MARKET_DATA_INSUFFICIENT",
                        "Exchange returned fewer than 50 candles");
            }
            return new FetchOutcome(
                    new MarketInstrumentSeries(instrument, candles),
                    new MarketDataSourceResult(
                            instrument.instrumentId(),
                            instrument.exchange().name(),
                            environment,
                            instrument.symbol(),
                            true,
                            candles.size(),
                            null,
                            null));
        } catch (IllegalArgumentException exception) {
            return failure(
                    instrument,
                    environment,
                    "MARKET_CAPABILITY_UNSUPPORTED",
                    exception.getMessage());
        } catch (MarketDataFetchException exception) {
            return failure(instrument, environment, exception.errorCode(), exception.getMessage());
        }
    }

    private static FetchOutcome failure(
            ResearchInstrument instrument,
            ExchangeEnvironment environment,
            String errorCode,
            String safeMessage) {
        return new FetchOutcome(
                null,
                new MarketDataSourceResult(
                        instrument.instrumentId(),
                        instrument.exchange().name(),
                        environment,
                        instrument.symbol(),
                        false,
                        0,
                        errorCode,
                        safeMessage));
    }

    private static void awaitAll(List<? extends CompletableFuture<?>> futures) {
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private static ExchangeEnvironment environment(
            io.omnnu.finbot.domain.catalog.ExchangeVenue exchange,
            ResearchDataPlane dataPlane) {
        if (dataPlane == ResearchDataPlane.LIVE) {
            return ExchangeEnvironment.LIVE;
        }
        return switch (exchange) {
            case GATE -> ExchangeEnvironment.TESTNET;
            case BYBIT -> ExchangeEnvironment.DEMO;
        };
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record FetchOutcome(
            MarketInstrumentSeries series,
            MarketDataSourceResult source) {
    }
}
