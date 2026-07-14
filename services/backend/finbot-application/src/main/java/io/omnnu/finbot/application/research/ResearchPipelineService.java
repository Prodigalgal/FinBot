package io.omnnu.finbot.application.research;

import io.omnnu.finbot.application.ingestion.IngestionUseCase;
import io.omnnu.finbot.application.market.MarketDataUseCase;
import io.omnnu.finbot.application.quant.QuantResearchUseCase;
import io.omnnu.finbot.application.trading.TradeAutomationUseCase;
import io.omnnu.finbot.application.workflow.StartWorkflowCommand;
import io.omnnu.finbot.application.workflow.StartWorkflowResult;
import io.omnnu.finbot.application.workflow.StartWorkflowUseCase;
import io.omnnu.finbot.application.workflow.WorkflowExecutionUseCase;
import io.omnnu.finbot.application.workflow.WorkflowRunFailureUseCase;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class ResearchPipelineService implements ResearchPipelineUseCase {
    private final StartWorkflowUseCase startWorkflow;
    private final IngestionUseCase ingestion;
    private final CompressionUseCase compression;
    private final MarketDataUseCase marketData;
    private final QuantResearchUseCase quantResearch;
    private final WorkflowExecutionUseCase workflowExecution;
    private final WorkflowRunFailureUseCase workflowFailure;
    private final TradeAutomationUseCase tradeAutomation;
    private final Clock clock;

    public ResearchPipelineService(
            StartWorkflowUseCase startWorkflow,
            IngestionUseCase ingestion,
            CompressionUseCase compression,
            MarketDataUseCase marketData,
            QuantResearchUseCase quantResearch,
            WorkflowExecutionUseCase workflowExecution,
            WorkflowRunFailureUseCase workflowFailure,
            TradeAutomationUseCase tradeAutomation,
            Clock clock) {
        this.startWorkflow = Objects.requireNonNull(startWorkflow, "startWorkflow");
        this.ingestion = Objects.requireNonNull(ingestion, "ingestion");
        this.compression = Objects.requireNonNull(compression, "compression");
        this.marketData = Objects.requireNonNull(marketData, "marketData");
        this.quantResearch = Objects.requireNonNull(quantResearch, "quantResearch");
        this.workflowExecution = Objects.requireNonNull(workflowExecution, "workflowExecution");
        this.workflowFailure = Objects.requireNonNull(workflowFailure, "workflowFailure");
        this.tradeAutomation = Objects.requireNonNull(tradeAutomation, "tradeAutomation");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<StartWorkflowResult> execute(StartWorkflowCommand command) {
        Objects.requireNonNull(command, "command");
        return startWorkflow.start(command).thenCompose(started -> prepare(started, command)
                .whenComplete((ignored, failure) -> recordPreparationFailure(started, failure))
                .thenCompose(ignored -> workflowExecution.execute(started.runId()))
                .thenCompose(ignored -> tradeAutomation.execute(started.runId()))
                .thenApply(ignored -> started));
    }

    private CompletionStage<Void> prepare(
            StartWorkflowResult started,
            StartWorkflowCommand command) {
        return executeStage(
                        PreparationStage.INGESTION,
                        () -> ingestion.collectEnabled(started.runId(), command.requestSummary()))
                .thenCompose(ignored -> executeStage(
                        PreparationStage.COMPRESSION,
                        () -> compression.compress(started.runId())))
                .thenCompose(ignored -> executeStage(
                        PreparationStage.MARKET_DATA,
                        () -> marketData.prepare(started.runId())))
                .thenCompose(prepared -> executeStage(
                        PreparationStage.QUANT_RESEARCH,
                        () -> quantResearch.execute(started.runId(), prepared)))
                .thenApply(ignored -> null);
    }

    private void recordPreparationFailure(
            StartWorkflowResult started,
            Throwable failure) {
        if (failure == null) {
            return;
        }
        var preparationFailure = findPreparationFailure(failure);
        if (preparationFailure == null) {
            return;
        }
        var cause = preparationFailure.getCause();
        try {
            workflowFailure.fail(
                    started.runId(),
                    preparationFailure.stage().errorCode(),
                    preparationFailure.stage().safeMessage(),
                    !(cause instanceof IllegalArgumentException),
                    clock.instant());
        } catch (RuntimeException recordingFailure) {
            failure.addSuppressed(recordingFailure);
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
