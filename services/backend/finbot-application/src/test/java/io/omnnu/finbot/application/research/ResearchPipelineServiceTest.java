package io.omnnu.finbot.application.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.application.ingestion.IngestionBatchResult;
import io.omnnu.finbot.application.ingestion.IngestionUseCase;
import io.omnnu.finbot.application.ingestion.CreateSourceCommand;
import io.omnnu.finbot.application.ingestion.DeleteSourceCommand;
import io.omnnu.finbot.application.ingestion.NormalizedDocument;
import io.omnnu.finbot.application.ingestion.SourceCollectionSummary;
import io.omnnu.finbot.application.ingestion.UpdateSourceCommand;
import io.omnnu.finbot.application.market.MarketDataUseCase;
import io.omnnu.finbot.application.operations.ResearchTaskMode;
import io.omnnu.finbot.application.quant.QuantResearchUseCase;
import io.omnnu.finbot.application.trading.TradeAutomationUseCase;
import io.omnnu.finbot.application.workflow.StartWorkflowCommand;
import io.omnnu.finbot.application.workflow.StartWorkflowResult;
import io.omnnu.finbot.application.workflow.StartWorkflowUseCase;
import io.omnnu.finbot.application.workflow.WorkflowExecutionUseCase;
import io.omnnu.finbot.application.workflow.WorkflowRunFailureUseCase;
import io.omnnu.finbot.application.workflow.WorkflowRunQuery;
import io.omnnu.finbot.application.workflow.WorkflowRunSnapshot;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.research.ResearchArtifactId;
import io.omnnu.finbot.domain.research.ResearchCaseId;
import io.omnnu.finbot.domain.research.ResearchCaseStatus;
import io.omnnu.finbot.domain.research.ResearchSegmentId;
import io.omnnu.finbot.domain.workflow.WorkflowEventId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
                runId -> new ResearchWorkflowPlan(true, true, true, true),
                ingestion,
                compression,
                marketData,
                quantResearch,
                workflowExecution,
                workflowFailure,
                workflowRuns(WorkflowRunStatus.ACCEPTED),
                tradeAutomation,
                segmentation(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var thrown = assertThrows(
                CompletionException.class,
                () -> service.execute(new ResearchPipelineRequest(
                                command(), ResearchTaskMode.STANDARD, 3, 3))
                        .toCompletableFuture()
                        .join());

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

    @Test
    void retryablePreparationFailureDoesNotTerminalizeBeforeFinalAttempt() {
        var started = new StartWorkflowResult(
                RUN_ID,
                new WorkflowEventId("event_pipeline_test001"),
                NOW);
        StartWorkflowUseCase startWorkflow = command -> CompletableFuture.completedFuture(started);
        CompressionUseCase compression = runId -> CompletableFuture.completedFuture(
                new CompressionBatchResult(
                        new ResearchArtifactId("artifact_compression_test001"),
                        1,
                        0,
                        0));
        var originalFailure = new RuntimeException("Temporary market timeout");
        MarketDataUseCase marketData = runId -> CompletableFuture.failedStage(originalFailure);
        var recordedFailure = new AtomicReference<RecordedFailure>();
        var service = new ResearchPipelineService(
                startWorkflow,
                runId -> new ResearchWorkflowPlan(true, true, true, true),
                successfulIngestion(),
                compression,
                marketData,
                (runId, prepared) -> CompletableFuture.failedStage(
                        new AssertionError("Quant research must not start")),
                runId -> CompletableFuture.failedStage(
                        new AssertionError("Debate must not start")),
                (runId, code, message, retryable, failedAt) -> {
                    recordedFailure.set(new RecordedFailure(runId, code, message, retryable, failedAt));
                    return true;
                },
                workflowRuns(WorkflowRunStatus.ACCEPTED),
                runId -> CompletableFuture.failedStage(
                        new AssertionError("Trade automation must not start")),
                segmentation(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var thrown = assertThrows(
                CompletionException.class,
                () -> service.execute(new ResearchPipelineRequest(
                                command(), ResearchTaskMode.STANDARD, 1, 3))
                        .toCompletableFuture()
                        .join());

        assertEquals(originalFailure, rootCause(thrown));
        assertNull(recordedFailure.get());
    }

    @Test
    void automaticRetryDoesNotReenterFailedWorkflow() {
        var started = new StartWorkflowResult(
                RUN_ID,
                new WorkflowEventId("event_pipeline_test001"),
                NOW);
        StartWorkflowUseCase startWorkflow = command -> CompletableFuture.completedFuture(started);
        var service = new ResearchPipelineService(
                startWorkflow,
                runId -> new ResearchWorkflowPlan(true, true, true, true),
                rejectingIngestion(),
                runId -> CompletableFuture.failedStage(new AssertionError("Compression must not start")),
                runId -> CompletableFuture.failedStage(new AssertionError("Market data must not start")),
                (runId, prepared) -> CompletableFuture.failedStage(new AssertionError("Quant must not start")),
                runId -> CompletableFuture.failedStage(new AssertionError("Workflow must not start")),
                (runId, code, message, retryable, failedAt) -> {
                    throw new AssertionError("Terminal failure must not be published twice");
                },
                workflowRuns(WorkflowRunStatus.FAILED),
                runId -> CompletableFuture.failedStage(new AssertionError("Trading must not start")),
                segmentation(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var thrown = assertThrows(
                CompletionException.class,
                () -> service.execute(new ResearchPipelineRequest(
                                command(), ResearchTaskMode.STANDARD, 2, 3))
                        .toCompletableFuture()
                        .join());

        assertEquals(
                "Workflow run run_pipeline_test001 is already FAILED",
                rootCause(thrown).getMessage());
    }

    @Test
    void startsLiveAndDemoBranchesIndependentlyAfterEvidenceCompression() {
        var demoRunId = new WorkflowRunId("run_pipeline_demo001");
        var startCount = new AtomicInteger();
        StartWorkflowUseCase startWorkflow = command -> {
            var sequence = startCount.incrementAndGet();
            return CompletableFuture.completedFuture(new StartWorkflowResult(
                    sequence == 1 ? RUN_ID : demoRunId,
                    new WorkflowEventId(sequence == 1
                            ? "event_pipeline_live001"
                            : "event_pipeline_demo001"),
                    NOW));
        };
        var liveCompletion = new CompletableFuture<Void>();
        var demoCompletion = new CompletableFuture<Void>();
        var startedBranches = ConcurrentHashMap.<WorkflowRunId>newKeySet();
        WorkflowExecutionUseCase workflowExecution = runId -> {
            startedBranches.add(runId);
            return runId.equals(RUN_ID) ? liveCompletion : demoCompletion;
        };
        var segmentation = new SnapshotSegmentationStore();
        var service = new ResearchPipelineService(
                startWorkflow,
                runId -> new ResearchWorkflowPlan(true, true, false, false),
                successfulIngestion(),
                runId -> CompletableFuture.completedFuture(new CompressionBatchResult(
                        new ResearchArtifactId("artifact_compression_test001"),
                        1,
                        0,
                        0)),
                runId -> CompletableFuture.failedStage(new AssertionError("Market data must not start")),
                (runId, prepared) -> CompletableFuture.failedStage(new AssertionError("Quant must not start")),
                workflowExecution,
                (runId, code, message, retryable, failedAt) -> true,
                workflowRuns(WorkflowRunStatus.ACCEPTED),
                runId -> CompletableFuture.failedStage(new AssertionError("Trading must not start")),
                new ResearchSegmentationService(segmentation),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.execute(new ResearchPipelineRequest(
                        command(), ResearchTaskMode.STANDARD, 1, 3))
                .toCompletableFuture();

        assertEquals(Set.of(RUN_ID, demoRunId), startedBranches);
        assertEquals(2, startCount.get());
        assertFalse(result.isDone());
        liveCompletion.complete(null);
        assertFalse(result.isDone());
        demoCompletion.complete(null);
        assertEquals(RUN_ID, result.join().runId());
        assertTrue(segmentation.demoRegistered);
    }

    @Test
    void workflowWithoutCompressionSkipsEvidenceAndRunsOnlyTheLiveBranch() {
        var startCount = new AtomicInteger();
        var segmentation = new SnapshotSegmentationStore();
        var service = new ResearchPipelineService(
                command -> {
                    startCount.incrementAndGet();
                    return CompletableFuture.completedFuture(new StartWorkflowResult(
                            RUN_ID,
                            new WorkflowEventId("event_pipeline_live001"),
                            NOW));
                },
                runId -> new ResearchWorkflowPlan(false, false, false, false),
                rejectingIngestion(),
                runId -> CompletableFuture.failedStage(new AssertionError("Compression must not start")),
                runId -> CompletableFuture.failedStage(new AssertionError("Market data must not start")),
                (runId, prepared) -> CompletableFuture.failedStage(new AssertionError("Quant must not start")),
                runId -> CompletableFuture.completedFuture(null),
                (runId, code, message, retryable, failedAt) -> true,
                workflowRuns(WorkflowRunStatus.ACCEPTED),
                runId -> CompletableFuture.failedStage(new AssertionError("Trading must not start")),
                new ResearchSegmentationService(segmentation),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.execute(new ResearchPipelineRequest(
                        command(), ResearchTaskMode.STANDARD, 1, 3))
                .toCompletableFuture()
                .join();

        assertEquals(RUN_ID, result.runId());
        assertEquals(1, startCount.get());
        assertEquals(
                io.omnnu.finbot.domain.research.ResearchSegmentStatus.SKIPPED,
                segmentation.evidenceStatus);
        assertFalse(segmentation.demoRegistered);
    }

    @Test
    void reusesExistingEvidenceSnapshotWithoutCollectingOrCompressingAgain() {
        var demoRunId = new WorkflowRunId("run_pipeline_demo001");
        var startCount = new AtomicInteger();
        var segmentation = new SnapshotSegmentationStore();
        segmentation.ensureLiveCase(
                new ResearchCaseId("case_pipeline_test001"),
                new ResearchSegmentId("segment_evidence_test001"),
                new ResearchSegmentId("segment_live_test001"),
                RUN_ID,
                WorkflowTrigger.SCHEDULED,
                "Execute scheduled research",
                NOW);
        segmentation.recordEvidenceSnapshot(
                RUN_ID,
                new ResearchArtifactId("artifact_compression_test001"),
                NOW);
        var executedRuns = ConcurrentHashMap.<WorkflowRunId>newKeySet();
        var service = new ResearchPipelineService(
                command -> {
                    var sequence = startCount.incrementAndGet();
                    return CompletableFuture.completedFuture(new StartWorkflowResult(
                            sequence == 1 ? RUN_ID : demoRunId,
                            new WorkflowEventId(sequence == 1
                                    ? "event_pipeline_live001"
                                    : "event_pipeline_demo001"),
                            NOW));
                },
                runId -> new ResearchWorkflowPlan(true, true, false, false),
                rejectingIngestion(),
                runId -> CompletableFuture.failedStage(
                        new AssertionError("Compression must not restart after snapshot binding")),
                runId -> CompletableFuture.failedStage(new AssertionError("Market data must not start")),
                (runId, prepared) -> CompletableFuture.failedStage(
                        new AssertionError("Quant research must not start")),
                runId -> {
                    executedRuns.add(runId);
                    return CompletableFuture.completedFuture(null);
                },
                (runId, code, message, retryable, failedAt) -> true,
                workflowRuns(WorkflowRunStatus.ACCEPTED),
                runId -> CompletableFuture.failedStage(
                        new AssertionError("Trade automation must not start")),
                new ResearchSegmentationService(segmentation),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.execute(new ResearchPipelineRequest(
                        command(), ResearchTaskMode.STANDARD, 2, 3))
                .toCompletableFuture()
                .join();

        assertEquals(RUN_ID, result.runId());
        assertEquals(Set.of(RUN_ID, demoRunId), executedRuns);
        assertEquals(2, startCount.get());
        assertTrue(segmentation.demoRegistered);
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
            public InformationSource createSource(CreateSourceCommand command) {
                throw new UnsupportedOperationException("Not used by the research pipeline test");
            }

            @Override
            public InformationSource updateSource(UpdateSourceCommand command) {
                throw new UnsupportedOperationException("Not used by the research pipeline test");
            }

            @Override
            public void deleteSource(DeleteSourceCommand command) {
                throw new UnsupportedOperationException("Not used by the research pipeline test");
            }

            @Override
            public InformationSource setSourceEnabled(SourceId sourceId, boolean enabled, long expectedVersion) {
                throw new UnsupportedOperationException("Not used by the research pipeline test");
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

            @Override
            public java.util.concurrent.CompletionStage<SourceCollectionSummary> testSource(
                    SourceId sourceId,
                    String query) {
                throw new UnsupportedOperationException("Not used by the research pipeline test");
            }
        };
    }

    private static IngestionUseCase rejectingIngestion() {
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
            public InformationSource createSource(CreateSourceCommand command) {
                throw new UnsupportedOperationException("Not used by the research pipeline test");
            }

            @Override
            public InformationSource updateSource(UpdateSourceCommand command) {
                throw new UnsupportedOperationException("Not used by the research pipeline test");
            }

            @Override
            public void deleteSource(DeleteSourceCommand command) {
                throw new UnsupportedOperationException("Not used by the research pipeline test");
            }

            @Override
            public InformationSource setSourceEnabled(SourceId sourceId, boolean enabled, long expectedVersion) {
                throw new UnsupportedOperationException("Not used by the research pipeline test");
            }

            @Override
            public java.util.concurrent.CompletionStage<IngestionBatchResult> collectEnabled(
                    WorkflowRunId workflowRunId,
                    String requestSummary) {
                return CompletableFuture.failedStage(new AssertionError("Ingestion must not start"));
            }

            @Override
            public java.util.concurrent.CompletionStage<SourceCollectionSummary> collectSource(
                    WorkflowRunId workflowRunId,
                    SourceId sourceId,
                    String query) {
                throw new UnsupportedOperationException("Not used by the research pipeline test");
            }

            @Override
            public java.util.concurrent.CompletionStage<SourceCollectionSummary> testSource(
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

    private static WorkflowRunQuery workflowRuns(WorkflowRunStatus status) {
        return runId -> Optional.of(new WorkflowRunSnapshot(
                runId,
                WorkflowType.SCHEDULED_RESEARCH,
                status,
                WorkflowTrigger.SCHEDULED,
                "Execute scheduled research",
                NOW,
                NOW));
    }

    private static ResearchSegmentationService segmentation() {
        return new ResearchSegmentationService(new ResearchSegmentationStore() {
            @Override
            public void ensureLiveCase(
                    io.omnnu.finbot.domain.research.ResearchCaseId caseId,
                    io.omnnu.finbot.domain.research.ResearchSegmentId evidenceSegmentId,
                    io.omnnu.finbot.domain.research.ResearchSegmentId liveSegmentId,
                    WorkflowRunId liveRunId,
                    WorkflowTrigger trigger,
                    String requestSummary,
                    Instant startedAt) {
            }

            @Override
            public void recordEvidenceSnapshot(
                    WorkflowRunId liveRunId,
                    ResearchArtifactId artifactId,
                    Instant completedAt) {
            }

            @Override
            public void transitionEvidence(
                    WorkflowRunId liveRunId,
                    io.omnnu.finbot.domain.research.ResearchSegmentStatus status,
                    String errorCode,
                    String errorMessage,
                    Instant changedAt) {
            }

            @Override
            public void registerDemoBranch(
                    WorkflowRunId liveRunId,
                    io.omnnu.finbot.domain.research.ResearchSegmentId demoSegmentId,
                    WorkflowRunId demoRunId,
                    Instant startedAt) {
            }

            @Override
            public void transition(
                    WorkflowRunId workflowRunId,
                    io.omnnu.finbot.domain.research.ResearchSegmentStatus status,
                    String errorCode,
                    String errorMessage,
                    Instant changedAt) {
            }

            @Override
            public Optional<ResearchCaseView> findByRunId(WorkflowRunId workflowRunId) {
                return Optional.empty();
            }
        });
    }

    private static final class SnapshotSegmentationStore implements ResearchSegmentationStore {
        private ResearchCaseId caseId;
        private WorkflowRunId liveRunId;
        private ResearchArtifactId evidenceArtifactId;
        private boolean demoRegistered;
        private io.omnnu.finbot.domain.research.ResearchSegmentStatus evidenceStatus;

        @Override
        public void ensureLiveCase(
                ResearchCaseId nextCaseId,
                ResearchSegmentId evidenceSegmentId,
                ResearchSegmentId liveSegmentId,
                WorkflowRunId nextLiveRunId,
                WorkflowTrigger trigger,
                String requestSummary,
                Instant startedAt) {
            caseId = nextCaseId;
            liveRunId = nextLiveRunId;
        }

        @Override
        public void recordEvidenceSnapshot(
                WorkflowRunId workflowRunId,
                ResearchArtifactId artifactId,
                Instant completedAt) {
            assertEquals(liveRunId, workflowRunId);
            evidenceArtifactId = artifactId;
        }

        @Override
        public void transitionEvidence(
                WorkflowRunId workflowRunId,
                io.omnnu.finbot.domain.research.ResearchSegmentStatus status,
                String errorCode,
                String errorMessage,
                Instant changedAt) {
            evidenceStatus = status;
        }

        @Override
        public void registerDemoBranch(
                WorkflowRunId workflowRunId,
                ResearchSegmentId demoSegmentId,
                WorkflowRunId demoRunId,
                Instant startedAt) {
            assertEquals(liveRunId, workflowRunId);
            demoRegistered = true;
        }

        @Override
        public void transition(
                WorkflowRunId workflowRunId,
                io.omnnu.finbot.domain.research.ResearchSegmentStatus status,
                String errorCode,
                String errorMessage,
                Instant changedAt) {
        }

        @Override
        public Optional<ResearchCaseView> findByRunId(WorkflowRunId workflowRunId) {
            if (!workflowRunId.equals(liveRunId) || evidenceArtifactId == null) {
                return Optional.empty();
            }
            return Optional.of(new ResearchCaseView(
                    caseId,
                    ResearchCaseStatus.RUNNING,
                    "Execute scheduled research",
                    evidenceArtifactId,
                    List.of(),
                    NOW,
                    null,
                    NOW));
        }
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
