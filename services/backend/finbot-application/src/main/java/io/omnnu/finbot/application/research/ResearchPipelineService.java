package io.omnnu.finbot.application.research;

import io.omnnu.finbot.application.ingestion.IngestionUseCase;
import io.omnnu.finbot.application.market.MarketDataUseCase;
import io.omnnu.finbot.application.quant.QuantResearchUseCase;
import io.omnnu.finbot.application.trading.TradeAutomationUseCase;
import io.omnnu.finbot.application.workflow.StartWorkflowCommand;
import io.omnnu.finbot.application.workflow.StartWorkflowResult;
import io.omnnu.finbot.application.workflow.StartWorkflowUseCase;
import io.omnnu.finbot.application.workflow.WorkflowExecutionUseCase;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class ResearchPipelineService implements ResearchPipelineUseCase {
    private final StartWorkflowUseCase startWorkflow;
    private final IngestionUseCase ingestion;
    private final CompressionUseCase compression;
    private final MarketDataUseCase marketData;
    private final QuantResearchUseCase quantResearch;
    private final WorkflowExecutionUseCase workflowExecution;
    private final TradeAutomationUseCase tradeAutomation;

    public ResearchPipelineService(
            StartWorkflowUseCase startWorkflow,
            IngestionUseCase ingestion,
            CompressionUseCase compression,
            MarketDataUseCase marketData,
            QuantResearchUseCase quantResearch,
            WorkflowExecutionUseCase workflowExecution,
            TradeAutomationUseCase tradeAutomation) {
        this.startWorkflow = Objects.requireNonNull(startWorkflow, "startWorkflow");
        this.ingestion = Objects.requireNonNull(ingestion, "ingestion");
        this.compression = Objects.requireNonNull(compression, "compression");
        this.marketData = Objects.requireNonNull(marketData, "marketData");
        this.quantResearch = Objects.requireNonNull(quantResearch, "quantResearch");
        this.workflowExecution = Objects.requireNonNull(workflowExecution, "workflowExecution");
        this.tradeAutomation = Objects.requireNonNull(tradeAutomation, "tradeAutomation");
    }

    @Override
    public CompletionStage<StartWorkflowResult> execute(StartWorkflowCommand command) {
        Objects.requireNonNull(command, "command");
        return startWorkflow.start(command).thenCompose(started -> ingestion
                .collectEnabled(started.runId(), command.requestSummary())
                .thenCompose(ignored -> compression.compress(started.runId()))
                .thenCompose(ignored -> marketData.prepare(started.runId()))
                .thenCompose(prepared -> quantResearch.execute(started.runId(), prepared))
                .thenCompose(ignored -> workflowExecution.execute(started.runId()))
                .thenCompose(ignored -> tradeAutomation.execute(started.runId()))
                .thenApply(ignored -> started));
    }
}
