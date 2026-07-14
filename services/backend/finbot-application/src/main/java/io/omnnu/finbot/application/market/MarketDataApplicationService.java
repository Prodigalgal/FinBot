package io.omnnu.finbot.application.market;

import io.omnnu.finbot.domain.quant.ArtifactKind;
import io.omnnu.finbot.domain.quant.ResearchArtifact;
import io.omnnu.finbot.domain.research.ResearchArtifactId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
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
    private final MarketDataArtifactEncoder artifactEncoder;
    private final MarketDataArtifactUriFactory artifactUriFactory;
    private final Clock clock;
    private final Executor executor;

    public MarketDataApplicationService(
            MarketDataRepository repository,
            MarketDataGateway gateway,
            MarketDataArtifactEncoder artifactEncoder,
            MarketDataArtifactUriFactory artifactUriFactory,
            Clock clock,
            Executor executor) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.artifactEncoder = Objects.requireNonNull(artifactEncoder, "artifactEncoder");
        this.artifactUriFactory = Objects.requireNonNull(artifactUriFactory, "artifactUriFactory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletionStage<MarketDataPreparationResult> prepare(WorkflowRunId workflowRunId) {
        Objects.requireNonNull(workflowRunId, "workflowRunId");
        return CompletableFuture.supplyAsync(() -> prepareSynchronously(workflowRunId), executor);
    }

    @Override
    public CompletionStage<MarketDataRefreshResult> refresh(
            io.omnnu.finbot.domain.catalog.InstrumentId instrumentId) {
        Objects.requireNonNull(instrumentId, "instrumentId");
        return CompletableFuture.supplyAsync(() -> refreshSynchronously(instrumentId), executor);
    }

    private MarketDataRefreshResult refreshSynchronously(
            io.omnnu.finbot.domain.catalog.InstrumentId instrumentId) {
        var instrument = repository.findInstrument(instrumentId)
                .orElseThrow(() -> new MarketDataFetchException(
                        "INSTRUMENT_NOT_FOUND",
                        "Active venue instrument was not found"));
        var outcome = fetch(instrument);
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

    private MarketDataPreparationResult prepareSynchronously(WorkflowRunId workflowRunId) {
        var instruments = repository.listResearchInstruments();
        if (instruments.isEmpty()) {
            throw new MarketDataFetchException(
                    "RESEARCH_UNIVERSE_EMPTY",
                    "No active venue instruments are available in the default watchlist");
        }
        var futures = instruments.stream()
                .map(instrument -> CompletableFuture.supplyAsync(() -> fetch(instrument), executor))
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
        var encoded = artifactEncoder.encode(series);
        var artifactIdentity = sha256(workflowRunId.value() + ':' + encoded.sha256Hex());
        var artifactId = new ResearchArtifactId("artifact_" + artifactIdentity.substring(0, 40));
        repository.saveArtifact(new MarketDataArtifactRecord(
                artifactId,
                workflowRunId,
                1,
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
                series.stream().map(MarketInstrumentSeries::instrument).toList(),
                outcomes.stream().map(FetchOutcome::source).toList());
    }

    private FetchOutcome fetch(ResearchInstrument instrument) {
        try {
            var candles = gateway.fetchCandles(
                    instrument,
                    RESEARCH_INTERVAL_SECONDS,
                    RESEARCH_CANDLE_LIMIT);
            if (candles.size() < 50) {
                return failure(
                        instrument,
                        "MARKET_DATA_INSUFFICIENT",
                        "Exchange returned fewer than 50 candles");
            }
            return new FetchOutcome(
                    new MarketInstrumentSeries(instrument, candles),
                    new MarketDataSourceResult(
                            instrument.instrumentId(),
                            instrument.exchange().name(),
                            instrument.symbol(),
                            true,
                            candles.size(),
                            null,
                            null));
        } catch (MarketDataFetchException exception) {
            return failure(instrument, exception.errorCode(), exception.getMessage());
        }
    }

    private static FetchOutcome failure(
            ResearchInstrument instrument,
            String errorCode,
            String safeMessage) {
        return new FetchOutcome(
                null,
                new MarketDataSourceResult(
                        instrument.instrumentId(),
                        instrument.exchange().name(),
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
