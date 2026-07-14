package io.omnnu.finbot.application.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.omnnu.finbot.application.ingestion.IngestionBatchResult;
import io.omnnu.finbot.application.ingestion.IngestionUseCase;
import io.omnnu.finbot.application.ingestion.NormalizedDocument;
import io.omnnu.finbot.application.ingestion.SourceCollectionSummary;
import io.omnnu.finbot.application.market.MarketDataUseCase;
import io.omnnu.finbot.application.quant.QuantResearchUseCase;
import io.omnnu.finbot.application.trading.TradeAutomationUseCase;
import io.omnnu.finbot.application.workflow.StartWorkflowCommand;
import io.omnnu.finbot.application.workflow.StartWorkflowResult;
import io.omnnu.finbot.application.workflow.StartWorkflowUseCase;
import io.omnnu.finbot.application.workflow.WorkflowExecutionUseCase;
import io.omnnu.finbot.application.workflow.WorkflowRunFailureUseCase;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.research.ResearchArtifactId;
import io.omnnu.finbot.domain.workflow.WorkflowEventId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ResearchPipelineServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-14T07:30:00Z");
    private static final WorkflowRunId RUN_ID = new WorkflowRunId("run_pipeline_test001");

    @Test
    void preparationFailureMarksTheWorkflowFailedAndPreservesTheOriginalCause() {
        var started = new StartWorkflowResult(
                RUN_ID,
                new WorkflowEventId("event_pipeline_test001"),
                NOW);
        StartWorkflowUseCase startWorkflow = command -> CompletableFuture.completedFuture(started);
        var ingestion = successfulIngestion();
        CompressionUseCase compression = runId -> CompletableFuture.completedFuture(
                new CompressionBatchResult(
                        new ResearchArtifactId("artifact_compression_test001"),
                        1,
                        0,
                        0));
        var originalFailure = new IllegalArgumentException("Invalid instrument symbol");
        MarketDataUseCase marketData = runId -> CompletableFuture.failedStage(originalFailure);
        QuantResearchUseCase quantResearch = (runId, prepared) -> {
            throw new AssertionError("Quant research must not start after market data failure");
        };
        WorkflowExecutionUseCase workflowExecution = runId -> {
            throw new AssertionError("Debate must not start after market data failure");
        };
        TradeAutomationUseCase tradeAutomation = runId -> {
            throw new AssertionError("Trade automation must not start after market data failure");
        };
        var recordedFailure = new AtomicReference<RecordedFailure>();
        WorkflowRunFailureUseCase workflowFailure = (runId, code, message, retryable, failedAt) -> {
            recordedFailure.set(new RecordedFailure(runId, code, message, retryable, failedAt));
            return true;
        };
        var service = new ResearchPipelineService(
                startWorkflow,
                ingestion,
                compression,
                marketData,
                quantResearch,
                workflowExecution,
                workflowFailure,
                tradeAutomation,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var thrown = assertThrows(
                CompletionException.class,
                () -> service.execute(command()).toCompletableFuture().join());

        assertEquals(originalFailure, rootCause(thrown));
        var failure = recordedFailure.get();
        assertEquals(RUN_ID, failure.runId());
        assertEquals("RESEARCH_MARKET_DATA_FAILED", failure.errorCode());
        assertEquals(
                "Market data preparation failed before workflow execution",
                failure.safeMessage());
        assertFalse(failure.retryable());
        assertEquals(NOW, failure.failedAt());
    }

    private static IngestionUseCase successfulIngestion() {
        return new IngestionUseCase() {
            @Override
            public List<InformationSource> listSources(boolean enabledOnly) {
                return List.of();
            }

            @Override
            public List<NormalizedDocument> listRecentDocuments(SourceId sourceId, int limit) {
                return List.of();
            }

            @Override
            public java.util.concurrent.CompletionStage<IngestionBatchResult> collectEnabled(
                    WorkflowRunId workflowRunId,
                    String requestSummary) {
                return CompletableFuture.completedFuture(new IngestionBatchResult(
                        new ResearchArtifactId("artifact_ingestion_test001"),
                        List.of(),
                        1,
                        1,
                        0));
            }

            @Override
            public java.util.concurrent.CompletionStage<SourceCollectionSummary> collectSource(
                    WorkflowRunId workflowRunId,
                    SourceId sourceId,
                    String query) {
                throw new UnsupportedOperationException("Not used by the research pipeline test");
            }
        };
    }

    private static StartWorkflowCommand command() {
        return new StartWorkflowCommand(
                WorkflowType.SCHEDULED_RESEARCH,
                WorkflowTrigger.SCHEDULED,
                null,
                "Execute scheduled research",
                "schedule:pipeline-test:20260714");
    }

    private static Throwable rootCause(Throwable failure) {
        var current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private record RecordedFailure(
            WorkflowRunId runId,
            String errorCode,
            String safeMessage,
            boolean retryable,
            Instant failedAt) {
    }
}
