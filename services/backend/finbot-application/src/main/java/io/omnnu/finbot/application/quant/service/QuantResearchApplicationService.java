package io.omnnu.finbot.application.quant.service;

import io.omnnu.finbot.application.quant.dto.QuantResearchExecutionResult;
import io.omnnu.finbot.application.quant.dto.QuantResearchExecutionStatus;
import io.omnnu.finbot.application.quant.port.in.QuantResearchUseCase;
import io.omnnu.finbot.application.quant.port.out.QuantResearchGateway;
import io.omnnu.finbot.application.quant.port.out.QuantResearchStore;

import io.omnnu.finbot.application.market.dto.MarketDataPreparationResult;
import io.omnnu.finbot.application.workflow.port.out.WorkflowEventPublisher;
import io.omnnu.finbot.application.workflow.port.out.WorkflowExecutionStore;
import io.omnnu.finbot.domain.catalog.MarketType;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.quant.QuantExchange;
import io.omnnu.finbot.domain.quant.QuantInstrument;
import io.omnnu.finbot.domain.quant.QuantMarketType;
import io.omnnu.finbot.domain.quant.QuantResearchEvent;
import io.omnnu.finbot.domain.quant.QuantResearchRequest;
import io.omnnu.finbot.domain.quant.QuantResearchSpecification;
import io.omnnu.finbot.domain.quant.ResearchCompletedEvent;
import io.omnnu.finbot.domain.quant.ResearchFailedEvent;
import io.omnnu.finbot.domain.quant.ResearchErrorCode;
import io.omnnu.finbot.domain.quant.ResearchKind;
import io.omnnu.finbot.domain.quant.ResearchProgressEvent;
import io.omnnu.finbot.domain.quant.ResearchRunId;
import io.omnnu.finbot.domain.quant.ResearchTimeRange;
import io.omnnu.finbot.domain.research.ResearchArtifactId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import io.omnnu.finbot.domain.workflow.WorkflowProgressed;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowStage;
import io.omnnu.finbot.domain.workflow.WorkflowStageStarted;
import java.time.Clock;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public final class QuantResearchApplicationService implements QuantResearchUseCase {
    private final QuantResearchGateway gateway;
    private final QuantResearchStore store;
    private final WorkflowExecutionStore workflowStore;
    private final WorkflowEventPublisher workflowEvents;
    private final Clock clock;

    public QuantResearchApplicationService(
            QuantResearchGateway gateway,
            QuantResearchStore store,
            WorkflowExecutionStore workflowStore,
            WorkflowEventPublisher workflowEvents,
            Clock clock) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.store = Objects.requireNonNull(store, "store");
        this.workflowStore = Objects.requireNonNull(workflowStore, "workflowStore");
        this.workflowEvents = Objects.requireNonNull(workflowEvents, "workflowEvents");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<QuantResearchExecutionResult> execute(
            WorkflowRunId workflowRunId,
            MarketDataPreparationResult marketData) {
        Objects.requireNonNull(workflowRunId, "workflowRunId");
        Objects.requireNonNull(marketData, "marketData");
        var workflow = workflowStore.load(workflowRunId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow run does not exist"));
        var quantNode = workflow.definitionVersion().nodes().stream()
                .filter(node -> node.enabled() && node.nodeType() == WorkflowNodeType.QUANT)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Workflow has no enabled QUANT node"));
        var researchIdentity = hash(workflowRunId.value() + ':' + marketData.artifact().sha256Hex());
        var researchRunId = new ResearchRunId("research_" + researchIdentity.substring(0, 40));
        var now = clock.instant();
        var strategyId = Objects.requireNonNullElse(quantNode.operation(), "multi_strategy_ensemble");
        var researchKind = "statistical_analysis".equals(strategyId)
                ? ResearchKind.STATISTICAL_ANALYSIS
                : ResearchKind.SIGNAL_EVALUATION;
        var request = new QuantResearchRequest(
                researchRunId,
                workflowRunId,
                "quant:" + workflowRunId.value() + ':' + marketData.artifact().sha256Hex().substring(0, 24),
                new QuantResearchSpecification(
                        researchKind,
                        marketData.instruments().stream()
                                .map(binding -> new QuantInstrument(
                                        QuantExchange.valueOf(binding.instrument().exchange().name()),
                                        binding.environment(),
                                        new InstrumentSymbol(binding.instrument().symbol()),
                                        quantMarketType(binding.instrument().marketType()),
                                        binding.instrument().quoteCurrency()))
                                .toList(),
                        new ResearchTimeRange(now.minus(Duration.ofDays(30)), now.plusSeconds(1)),
                        marketData.artifact(),
                        strategyId,
                        "2.0.0",
                        List.of(),
                        Math.floorMod(workflowRunId.value().hashCode(), Integer.MAX_VALUE)),
                now);
        store.start(request, marketData.artifactId());
        workflowEvents.publish(workflowRunId, (eventId, sequence, occurredAt) ->
                new WorkflowStageStarted(
                        eventId,
                        workflowRunId,
                        sequence,
                        WorkflowStage.QUANT_RESEARCH,
                        quantNode.nodeId(),
                        occurredAt));
        var collector = new QuantEventCollector(
                request,
                quantNode.nodeId());
        gateway.stream(request).subscribe(collector);
        return collector.result();
    }

    private final class QuantEventCollector implements Flow.Subscriber<QuantResearchEvent> {
        private final QuantResearchRequest request;
        private final io.omnnu.finbot.domain.workflow.WorkflowNodeId quantNodeId;
        private final CompletableFuture<QuantResearchExecutionResult> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private QuantEventCollector(
                QuantResearchRequest request,
                io.omnnu.finbot.domain.workflow.WorkflowNodeId quantNodeId) {
            this.request = request;
            this.quantNodeId = quantNodeId;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = Objects.requireNonNull(subscription, "subscription");
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(QuantResearchEvent event) {
            if (result.isDone()) {
                return;
            }
            try {
                store.appendEvent(event);
                switch (event) {
                    case ResearchProgressEvent progress -> publishProgress(progress);
                    case ResearchCompletedEvent completed -> complete(completed);
                    case ResearchFailedEvent failed -> fail(failed);
                    default -> {
                    }
                }
            } catch (RuntimeException exception) {
                cancel();
                result.completeExceptionally(exception);
            }
        }

        @Override
        public void onError(Throwable error) {
            store.failTransport(
                    request.researchRunId(),
                    ResearchErrorCode.INTERNAL,
                    "Quant research transport failed: " + error.getClass().getSimpleName(),
                    clock.instant());
            result.completeExceptionally(new IllegalStateException(
                    "Quant research stream failed: " + error.getClass().getSimpleName(),
                    error));
        }

        @Override
        public void onComplete() {
            if (!result.isDone()) {
                result.completeExceptionally(new IllegalStateException(
                        "Quant research stream ended without a terminal event"));
            }
        }

        private void publishProgress(ResearchProgressEvent progress) {
            workflowEvents.publish(request.workflowRunId(), (eventId, sequence, occurredAt) ->
                    new WorkflowProgressed(
                            eventId,
                            request.workflowRunId(),
                            sequence,
                            WorkflowStage.QUANT_RESEARCH,
                            quantNodeId,
                            progress.progressBasisPoints() / 100,
                            progress.safeSummary(),
                            occurredAt));
        }

        private void complete(ResearchCompletedEvent completed) {
            var artifactId = new ResearchArtifactId(
                    "artifact_" + hash(completed.researchRunId().value() + ":quant-result").substring(0, 40));
            store.complete(completed, artifactId, clock.instant());
            result.complete(new QuantResearchExecutionResult(
                    completed.researchRunId(),
                    QuantResearchExecutionStatus.COMPLETED,
                    artifactId,
                    completed.metrics(),
                    completed.observationCount(),
                    null,
                    null));
        }

        private void fail(ResearchFailedEvent failed) {
            store.fail(failed, clock.instant());
            result.complete(new QuantResearchExecutionResult(
                    failed.researchRunId(),
                    QuantResearchExecutionStatus.FAILED,
                    null,
                    List.of(),
                    0,
                    failed.code().name(),
                    failed.safeMessage()));
        }

        private void cancel() {
            if (subscription != null) {
                subscription.cancel();
            }
        }

        private CompletionStage<QuantResearchExecutionResult> result() {
            return result;
        }
    }

    private static QuantMarketType quantMarketType(MarketType marketType) {
        return switch (marketType) {
            case SPOT -> QuantMarketType.SPOT;
            case LINEAR_PERPETUAL, INVERSE_PERPETUAL -> QuantMarketType.PERPETUAL;
            case FUTURE -> QuantMarketType.FUTURE;
        };
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
