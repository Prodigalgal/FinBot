package io.omnnu.finbot.application.research.service;

import io.omnnu.finbot.application.research.dto.ResearchCaseView;
import io.omnnu.finbot.application.research.dto.ResearchPipelineRequest;
import io.omnnu.finbot.application.research.port.in.CompressionUseCase;
import io.omnnu.finbot.application.research.port.in.ResearchPipelineUseCase;
import io.omnnu.finbot.application.research.port.out.ResearchWorkflowPlanQuery;

import io.omnnu.finbot.application.ingestion.port.in.IngestionUseCase;
import io.omnnu.finbot.application.market.port.in.MarketDataUseCase;
import io.omnnu.finbot.application.operations.dto.ResearchTaskMode;
import io.omnnu.finbot.application.quant.port.in.QuantResearchUseCase;
import io.omnnu.finbot.application.trading.port.in.TradeAutomationUseCase;
import io.omnnu.finbot.application.shared.service.IdempotencyKeys;
import io.omnnu.finbot.application.workflow.dto.StartWorkflowResult;
import io.omnnu.finbot.application.workflow.port.in.StartWorkflowUseCase;
import io.omnnu.finbot.application.workflow.port.in.WorkflowExecutionUseCase;
import io.omnnu.finbot.application.workflow.port.in.WorkflowRunFailureUseCase;
import io.omnnu.finbot.application.workflow.port.out.WorkflowRunQuery;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.domain.research.ResearchDataPlane;
import io.omnnu.finbot.domain.research.ResearchSegmentStatus;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class ResearchPipelineService implements ResearchPipelineUseCase {
    private final StartWorkflowUseCase startWorkflow;
    private final ResearchWorkflowPlanQuery workflowPlans;
    private final IngestionUseCase ingestion;
    private final CompressionUseCase compression;
    private final MarketDataUseCase marketData;
    private final QuantResearchUseCase quantResearch;
    private final WorkflowExecutionUseCase workflowExecution;
    private final WorkflowRunFailureUseCase workflowFailure;
    private final WorkflowRunQuery workflowRuns;
    private final TradeAutomationUseCase tradeAutomation;
    private final ResearchSegmentationService segmentation;
    private final Clock clock;

    public ResearchPipelineService(
            StartWorkflowUseCase startWorkflow,
            ResearchWorkflowPlanQuery workflowPlans,
            IngestionUseCase ingestion,
            CompressionUseCase compression,
            MarketDataUseCase marketData,
            QuantResearchUseCase quantResearch,
            WorkflowExecutionUseCase workflowExecution,
            WorkflowRunFailureUseCase workflowFailure,
            WorkflowRunQuery workflowRuns,
            TradeAutomationUseCase tradeAutomation,
            ResearchSegmentationService segmentation,
            Clock clock) {
        this.startWorkflow = Objects.requireNonNull(startWorkflow, "startWorkflow");
        this.workflowPlans = Objects.requireNonNull(workflowPlans, "workflowPlans");
        this.ingestion = Objects.requireNonNull(ingestion, "ingestion");
        this.compression = Objects.requireNonNull(compression, "compression");
        this.marketData = Objects.requireNonNull(marketData, "marketData");
        this.quantResearch = Objects.requireNonNull(quantResearch, "quantResearch");
        this.workflowExecution = Objects.requireNonNull(workflowExecution, "workflowExecution");
        this.workflowFailure = Objects.requireNonNull(workflowFailure, "workflowFailure");
        this.workflowRuns = Objects.requireNonNull(workflowRuns, "workflowRuns");
        this.tradeAutomation = Objects.requireNonNull(tradeAutomation, "tradeAutomation");
        this.segmentation = Objects.requireNonNull(segmentation, "segmentation");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<StartWorkflowResult> execute(ResearchPipelineRequest request) {
        Objects.requireNonNull(request, "request");
        return startWorkflow.start(request.workflowCommand())
                .thenCompose(started -> executeSegmented(started, request));
    }

    private CompletionStage<StartWorkflowResult> executeSegmented(
            StartWorkflowResult liveStarted,
            ResearchPipelineRequest request) {
        segmentation.ensureLiveCase(
                liveStarted.runId(),
                request.workflowCommand().trigger(),
                request.workflowCommand().requestSummary(),
                liveStarted.acceptedAt());
        return prepareSharedEvidence(liveStarted, request)
                .thenCompose(ignored -> executeIndependentBranches(liveStarted, request));
    }

    private CompletionStage<Void> prepareSharedEvidence(
            StartWorkflowResult liveStarted,
            ResearchPipelineRequest request) {
        if (segmentation.findByRunId(liveStarted.runId())
                .map(ResearchCaseView::evidenceArtifactId)
                .isPresent()) {
            return CompletableFuture.completedFuture(null);
        }
        var status = workflowRuns.find(liveStarted.runId())
                .orElseThrow(() -> new IllegalStateException("Accepted workflow run is missing"))
                .status();
        CompletionStage<Void> preparation = switch (status) {
            case ACCEPTED -> prepareEvidence(
                    liveStarted,
                    request.workflowCommand().requestSummary());
            case RUNNING, WAITING_HUMAN, PARTIAL, COMPLETED ->
                    CompletableFuture.completedFuture(null);
            case FAILED -> request.taskMode() == ResearchTaskMode.RESUME_FAILED
                    ? prepareEvidence(liveStarted, request.workflowCommand().requestSummary())
                    : terminalRunFailure(liveStarted, status).thenApply(ignored -> null);
            case CANCELLED -> terminalRunFailure(liveStarted, status).thenApply(ignored -> null);
        };
        return preparation.whenComplete((ignored, failure) -> {
            recordPreparationFailure(liveStarted, request, failure);
            if (failure != null) {
                failEvidenceSegment(liveStarted, failure);
                transitionBranch(liveStarted, request, failure);
            }
        });
    }

    private CompletionStage<StartWorkflowResult> executeIndependentBranches(
            StartWorkflowResult liveStarted,
            ResearchPipelineRequest request) {
        var live = continueAfterEvidence(liveStarted, request, ResearchBranch.LIVE_RESEARCH)
                .handle(BranchOutcome::from);
        var demo = launchDemoBranch(liveStarted, request)
                .handle(BranchOutcome::from);
        return live.thenCombine(demo, BranchPair::new)
                .thenCompose(outcomes -> combinedResult(
                        liveStarted,
                        outcomes.live(),
                        outcomes.demo()));
    }

    private CompletionStage<StartWorkflowResult> continueAfterEvidence(
            StartWorkflowResult started,
            ResearchPipelineRequest request,
            ResearchBranch branch) {
        var status = workflowRuns.find(started.runId())
                .orElseThrow(() -> new IllegalStateException("Accepted workflow run is missing"))
                .status();
        CompletionStage<StartWorkflowResult> execution = switch (status) {
            case ACCEPTED -> prepareAnalysisAndExecute(started, request, branch);
            case RUNNING -> executeWorkflowAndOptionalValidation(started, branch);
            case WAITING_HUMAN -> CompletableFuture.completedFuture(started);
            case PARTIAL, COMPLETED -> executeOptionalValidation(started, branch);
            case FAILED -> request.taskMode() == ResearchTaskMode.RESUME_FAILED
                    ? prepareAnalysisAndExecute(started, request, branch)
                    : terminalRunFailure(started, status);
            case CANCELLED -> terminalRunFailure(started, status);
        };
        return execution.whenComplete((ignored, failure) ->
                transitionBranch(started, request, failure));
    }

    private CompletionStage<StartWorkflowResult> prepareAnalysisAndExecute(
            StartWorkflowResult started,
            ResearchPipelineRequest request,
            ResearchBranch branch) {
        var preparation = branch == ResearchBranch.LIVE_RESEARCH
                ? prepareLiveAnalysis(started, request.marketAnalysisScope())
                : prepareDemo(started, request.marketAnalysisScope());
        return preparation
                .whenComplete((ignored, failure) -> recordPreparationFailure(started, request, failure))
                .thenCompose(ignored -> executeWorkflowAndOptionalValidation(started, branch));
    }

    private CompletionStage<StartWorkflowResult> executeWorkflowAndOptionalValidation(
            StartWorkflowResult started,
            ResearchBranch branch) {
        return workflowExecution.execute(started.runId())
                .thenCompose(ignored -> executeOptionalValidation(started, branch));
    }

    private CompletionStage<StartWorkflowResult> executeOptionalValidation(
            StartWorkflowResult started,
            ResearchBranch branch) {
        if (branch != ResearchBranch.DEMO_AUTOTRADE
                || !workflowPlans.find(started.runId()).validateWithPaperTrading()) {
            return CompletableFuture.completedFuture(started);
        }
        return tradeAutomation.execute(started.runId()).thenApply(ignored -> started);
    }

    private static CompletionStage<StartWorkflowResult> terminalRunFailure(
            StartWorkflowResult started,
            WorkflowRunStatus status) {
        return CompletableFuture.failedStage(new IllegalStateException(
                "Workflow run " + started.runId().value() + " is already " + status));
    }

    private CompletionStage<Void> prepareEvidence(
            StartWorkflowResult started,
            String requestSummary) {
        var plan = workflowPlans.find(started.runId());
        CompletionStage<Void> preparation = CompletableFuture.completedFuture(null);
        if (plan.collectEvidence()) {
            preparation = preparation.thenCompose(ignored -> executeStage(
                            PreparationStage.INGESTION,
                            () -> ingestion.collectEnabled(started.runId(), requestSummary))
                    .thenApply(result -> null));
        }
        if (plan.compressEvidence()) {
            preparation = preparation.thenCompose(ignored -> executeStage(
                            PreparationStage.COMPRESSION,
                            () -> compression.compress(started.runId()))
                    .thenApply(result -> {
                        segmentation.recordEvidenceSnapshot(
                                started.runId(), result.artifactId(), clock.instant());
                        return null;
                    }));
        } else {
            preparation = preparation.thenRun(() ->
                    segmentation.skipEvidence(started.runId(), clock.instant()));
        }
        return preparation;
    }

    private CompletionStage<Void> prepareLiveAnalysis(
            StartWorkflowResult started,
            io.omnnu.finbot.application.market.dto.MarketAnalysisScope marketAnalysisScope) {
        var plan = workflowPlans.find(started.runId());
        if (!plan.runQuantResearch()) {
            return CompletableFuture.completedFuture(null);
        }
        return executeStage(
                        PreparationStage.MARKET_DATA,
                        () -> marketAnalysisScope == null
                                ? marketData.prepare(started.runId())
                                : marketData.prepare(started.runId(), marketAnalysisScope))
                .thenCompose(prepared -> executeStage(
                        PreparationStage.QUANT_RESEARCH,
                        () -> quantResearch.execute(started.runId(), prepared)))
                .thenApply(ignored -> null);
    }

    private CompletionStage<Void> prepareDemo(
            StartWorkflowResult started,
            io.omnnu.finbot.application.market.dto.MarketAnalysisScope liveScope) {
        var plan = workflowPlans.find(started.runId());
        if (!plan.runQuantResearch()) {
            return CompletableFuture.completedFuture(null);
        }
        var paperScope = paperScope(liveScope);
        return executeStage(
                        PreparationStage.MARKET_DATA,
                        () -> paperScope == null
                                ? marketData.prepare(started.runId(), ResearchDataPlane.PAPER)
                                : marketData.prepare(started.runId(), paperScope))
                .thenCompose(prepared -> executeStage(
                        PreparationStage.QUANT_RESEARCH,
                        () -> quantResearch.execute(started.runId(), prepared)))
                .thenApply(ignored -> null);
    }

    private CompletionStage<StartWorkflowResult> launchDemoBranch(
            StartWorkflowResult liveStarted,
            ResearchPipelineRequest request) {
        var researchCase = segmentation.findByRunId(liveStarted.runId());
        if (researchCase.isEmpty() || researchCase.orElseThrow().evidenceArtifactId() == null) {
            return CompletableFuture.completedFuture(liveStarted);
        }
        var command = request.workflowCommand();
        var demoCommand = new io.omnnu.finbot.application.workflow.dto.StartWorkflowCommand(
                command.workflowType(),
                command.trigger(),
                request.demoWorkflowVersionId() == null
                        ? command.workflowVersionId()
                        : request.demoWorkflowVersionId(),
                command.requestSummary(),
                IdempotencyKeys.scoped("demo-branch", command.idempotencyKey()));
        return startWorkflow.start(demoCommand).thenCompose(demoStarted -> {
            segmentation.registerDemoBranch(
                    liveStarted.runId(), demoStarted.runId(), demoStarted.acceptedAt());
            var demoRequest = new ResearchPipelineRequest(
                    demoCommand,
                    request.taskMode(),
                    request.attemptNumber(),
                    request.maximumAttempts(),
                    request.marketAnalysisScope(),
                    request.demoWorkflowVersionId());
            return continueAfterEvidence(demoStarted, demoRequest, ResearchBranch.DEMO_AUTOTRADE);
        });
    }

    private CompletionStage<StartWorkflowResult> combinedResult(
            StartWorkflowResult liveStarted,
            BranchOutcome liveOutcome,
            BranchOutcome demoOutcome) {
        if (liveOutcome.failure() != null) {
            return CompletableFuture.failedStage(liveOutcome.failure());
        }
        if (demoOutcome.failure() != null) {
            return CompletableFuture.failedStage(demoOutcome.failure());
        }
        return CompletableFuture.completedFuture(liveStarted);
    }

    private void transitionBranch(
            StartWorkflowResult started,
            ResearchPipelineRequest request,
            Throwable failure) {
        if (failure == null) {
            var status = workflowRuns.find(started.runId())
                    .map(snapshot -> snapshot.status())
                    .orElse(null);
            if (status == WorkflowRunStatus.COMPLETED || status == WorkflowRunStatus.PARTIAL) {
                segmentation.transition(
                        started.runId(), ResearchSegmentStatus.COMPLETED, null, null, clock.instant());
            }
            return;
        }
        var terminal = request.finalAttempt() || workflowRuns.find(started.runId())
                .map(snapshot -> snapshot.status() == WorkflowRunStatus.FAILED
                        || snapshot.status() == WorkflowRunStatus.CANCELLED)
                .orElse(false);
        if (!terminal) {
            return;
        }
        try {
            segmentation.transition(
                    started.runId(),
                    ResearchSegmentStatus.FAILED,
                    "RESEARCH_BRANCH_FAILED",
                    "Research workflow branch failed",
                    clock.instant());
        } catch (RuntimeException transitionFailure) {
            failure.addSuppressed(transitionFailure);
        }
    }

    private static io.omnnu.finbot.application.market.dto.MarketAnalysisScope paperScope(
            io.omnnu.finbot.application.market.dto.MarketAnalysisScope liveScope) {
        if (liveScope == null) {
            return null;
        }
        var environment = switch (liveScope.exchange()) {
            case GATE -> ExchangeEnvironment.TESTNET;
            case BYBIT -> ExchangeEnvironment.DEMO;
        };
        return new io.omnnu.finbot.application.market.dto.MarketAnalysisScope(
                liveScope.instrumentId(),
                liveScope.symbol(),
                liveScope.exchange(),
                environment,
                liveScope.intervalSeconds(),
                liveScope.forecastHorizonSeconds());
    }

    private void recordPreparationFailure(
            StartWorkflowResult started,
            ResearchPipelineRequest request,
            Throwable failure) {
        if (failure == null) {
            return;
        }
        var preparationFailure = findPreparationFailure(failure);
        if (preparationFailure == null) {
            return;
        }
        var cause = preparationFailure.getCause();
        var retryable = !(cause instanceof IllegalArgumentException);
        if (retryable && !request.finalAttempt()) {
            return;
        }
        try {
            workflowFailure.fail(
                    started.runId(),
                    preparationFailure.stage().errorCode(),
                    preparationFailure.stage().safeMessage(),
                    retryable,
                    clock.instant());
        } catch (RuntimeException recordingFailure) {
            failure.addSuppressed(recordingFailure);
        }
    }

    private void failEvidenceSegment(StartWorkflowResult started, Throwable failure) {
        var preparationFailure = findPreparationFailure(failure);
        var errorCode = preparationFailure == null
                ? "RESEARCH_EVIDENCE_FAILED"
                : preparationFailure.stage().errorCode();
        var safeMessage = preparationFailure == null
                ? "Shared evidence preparation failed"
                : preparationFailure.stage().safeMessage();
        try {
            segmentation.failEvidence(started.runId(), errorCode, safeMessage, clock.instant());
        } catch (RuntimeException transitionFailure) {
            failure.addSuppressed(transitionFailure);
        }
    }

    private static <T> CompletionStage<T> executeStage(
            PreparationStage stage,
            Supplier<CompletionStage<T>> operation) {
        try {
            return Objects.requireNonNull(operation.get(), "operation result")
                    .handle((result, failure) -> {
                        if (failure != null) {
                            throw new CompletionException(
                                    new ResearchPreparationException(stage, unwrap(failure)));
                        }
                        return result;
                    });
        } catch (RuntimeException failure) {
            return CompletableFuture.failedStage(
                    new ResearchPreparationException(stage, unwrap(failure)));
        }
    }

    private static ResearchPreparationException findPreparationFailure(Throwable failure) {
        var current = failure;
        while (current != null) {
            if (current instanceof ResearchPreparationException preparationFailure) {
                return preparationFailure;
            }
            current = current.getCause();
        }
        return null;
    }

    private static Throwable unwrap(Throwable failure) {
        var current = failure;
        while (current instanceof CompletionException
                && current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private enum PreparationStage {
        INGESTION(
                "RESEARCH_INGESTION_FAILED",
                "Information collection failed before workflow execution"),
        COMPRESSION(
                "RESEARCH_COMPRESSION_FAILED",
                "Evidence compression failed before workflow execution"),
        MARKET_DATA(
                "RESEARCH_MARKET_DATA_FAILED",
                "Market data preparation failed before workflow execution"),
        QUANT_RESEARCH(
                "RESEARCH_QUANT_FAILED",
                "Quantitative research failed before workflow execution");

        private final String errorCode;
        private final String safeMessage;

        PreparationStage(String errorCode, String safeMessage) {
            this.errorCode = errorCode;
            this.safeMessage = safeMessage;
        }

        private String errorCode() {
            return errorCode;
        }

        private String safeMessage() {
            return safeMessage;
        }
    }

    private enum ResearchBranch {
        LIVE_RESEARCH,
        DEMO_AUTOTRADE
    }

    private record BranchOutcome(StartWorkflowResult result, Throwable failure) {
        private static BranchOutcome from(StartWorkflowResult result, Throwable failure) {
            return new BranchOutcome(result, unwrap(failure));
        }
    }

    private record BranchPair(BranchOutcome live, BranchOutcome demo) {
    }

    private static final class ResearchPreparationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final PreparationStage stage;

        private ResearchPreparationException(PreparationStage stage, Throwable cause) {
            super(stage.safeMessage(), cause);
            this.stage = Objects.requireNonNull(stage, "stage");
        }

        private PreparationStage stage() {
            return stage;
        }
    }
}
