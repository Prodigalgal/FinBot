package io.omnnu.finbot.application.research.service;

import io.omnnu.finbot.application.research.dto.AiCompressionRecord;
import io.omnnu.finbot.application.research.dto.CompressionBatchResult;
import io.omnnu.finbot.application.research.dto.CompressionContent;
import io.omnnu.finbot.application.research.dto.CompressionPackage;
import io.omnnu.finbot.application.research.dto.CompressionStatus;
import io.omnnu.finbot.application.research.dto.EvidenceAiReview;
import io.omnnu.finbot.application.research.dto.EvidenceAiReviewStage;
import io.omnnu.finbot.application.research.port.out.CompressionOutputParser;
import io.omnnu.finbot.application.research.port.out.CompressionRepository;
import io.omnnu.finbot.application.research.service.CompressionApplicationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.application.ai.port.out.AiBudgetReservationStore;
import io.omnnu.finbot.application.ai.dto.AiCompletionEvent;
import io.omnnu.finbot.application.ai.dto.AiCompletionFailed;
import io.omnnu.finbot.application.ai.dto.AiCompletionFinished;
import io.omnnu.finbot.application.ai.port.out.AiCompletionGateway;
import io.omnnu.finbot.application.ai.dto.AiCompletionRequest;
import io.omnnu.finbot.application.ai.service.AiExecutionPolicyExecutor;
import io.omnnu.finbot.application.ai.port.out.AiInvocationAuditStore;
import io.omnnu.finbot.application.ai.dto.AiInvocationCompletion;
import io.omnnu.finbot.application.ai.dto.AiInvocationFailure;
import io.omnnu.finbot.application.ai.dto.AiInvocationStart;
import io.omnnu.finbot.application.ai.dto.AiRuntimeBinding;
import io.omnnu.finbot.application.ai.dto.AiStreamStarted;
import io.omnnu.finbot.application.ai.dto.AiTextDelta;
import io.omnnu.finbot.application.ai.dto.AiUsageReported;
import io.omnnu.finbot.application.ai.service.WorkflowAiInvoker;
import io.omnnu.finbot.application.ingestion.dto.NormalizedDocument;
import io.omnnu.finbot.application.ingestion.dto.ContentBlock;
import io.omnnu.finbot.application.shared.port.out.SortableIdGenerator;
import io.omnnu.finbot.application.workflow.port.out.WorkflowEventFactory;
import io.omnnu.finbot.application.workflow.port.out.WorkflowEventPublisher;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.application.workflow.port.out.WorkflowExecutionStore;
import io.omnnu.finbot.domain.ai.AiInvocationId;
import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.ingestion.DocumentId;
import io.omnnu.finbot.domain.ingestion.EvidenceId;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.ingestion.SourceTier;
import io.omnnu.finbot.domain.workflow.WorkflowActivationMode;
import io.omnnu.finbot.domain.workflow.WorkflowCanvasPosition;
import io.omnnu.finbot.domain.workflow.WorkflowContextMode;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionId;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeContextMode;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeId;
import io.omnnu.finbot.domain.workflow.WorkflowEvent;
import io.omnnu.finbot.domain.workflow.WorkflowEventId;
import io.omnnu.finbot.domain.workflow.WorkflowFailurePolicy;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import io.omnnu.finbot.domain.workflow.WorkflowOutputContract;
import io.omnnu.finbot.domain.workflow.WorkflowRetryPolicy;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import io.omnnu.finbot.domain.workflow.WorkflowVersionStatus;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class CompressionApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-16T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final WorkflowRunId RUN_ID = new WorkflowRunId("run_consensus_test");

    @Test
    void persistsEverySeatAndUsesOnlyValidatorOutputAsFinalCompression() {
        var version = version();
        var repository = new RecordingCompressionRepository(document());
        var gateway = new RecordingGateway(Set.of());

        var result = execute(version, repository, gateway);

        assertEquals(1, result.completedCount());
        assertEquals(0, result.failedCount());
        assertEquals(5, gateway.requests().size());
        assertEquals(5, repository.reviews().size());
        assertEquals(2, count(repository, EvidenceAiReviewStage.CLEANING, CompressionStatus.COMPLETED));
        assertEquals(2, count(repository, EvidenceAiReviewStage.COMPRESSION, CompressionStatus.COMPLETED));
        assertEquals(1, count(repository, EvidenceAiReviewStage.VALIDATION, CompressionStatus.COMPLETED));
        var finalCompression = repository.compressions().getFirst();
        assertEquals(CompressionStatus.COMPLETED, finalCompression.status());
        assertEquals("node_validator-call-1", finalCompression.content().summary());
        assertEquals(List.of("document_consensus", "evidence_consensus", "source_consensus", "b0"),
                finalCompression.content().citations());
    }

    @Test
    void promptsRequireAtomicFactsAndRejectDocumentMetaNarratives() {
        var repository = new RecordingCompressionRepository(document());
        var gateway = new RecordingGateway(Set.of());

        execute(version(), repository, gateway);

        assertEquals(5, gateway.requests().size());
        assertTrue(gateway.requests().stream()
                .allMatch(request -> request.userPrompt().contains("原子事实")));
        assertTrue(gateway.requests().stream()
                .allMatch(request -> request.userPrompt().contains("禁止输出‘本文讲述了’")));
        assertTrue(gateway.requests().stream()
                .allMatch(request -> request.userPrompt().contains("summary 是事实压缩正文而不是文章摘要")));
    }

    @Test
    void failsClosedWithoutInvokingValidatorWhenTwoCompressionCandidatesAreUnavailable() {
        var version = version();
        var repository = new RecordingCompressionRepository(document());
        var gateway = new RecordingGateway(Set.of(new WorkflowNodeId("node_compressor_b")));

        var result = execute(version, repository, gateway);

        assertEquals(0, result.completedCount());
        assertEquals(1, result.failedCount());
        assertEquals(4, gateway.requests().size());
        assertTrue(gateway.requests().stream()
                .noneMatch(request -> request.nodeId().value().equals("node_validator")));
        assertEquals(1, count(repository, EvidenceAiReviewStage.VALIDATION, CompressionStatus.FAILED));
        var finalCompression = repository.compressions().getFirst();
        assertEquals(CompressionStatus.FAILED, finalCompression.status());
        assertEquals("EVIDENCE_CONSENSUS_INSUFFICIENT", finalCompression.errorCode());
    }

    private static CompressionBatchResult execute(
            WorkflowDefinitionVersion version,
            RecordingCompressionRepository repository,
            RecordingGateway gateway) {
        var context = new WorkflowExecutionContext(
                RUN_ID,
                WorkflowRunStatus.ACCEPTED,
                "Analyze evidence consensus",
                "{}",
                version);
        var ids = new AtomicInteger();
        SortableIdGenerator idGenerator = prefix -> prefix + "test" + String.format("%08d", ids.incrementAndGet());
        var invoker = new WorkflowAiInvoker(
                gateway,
                ignored -> new AiRuntimeBinding(AiProtocol.CHAT, ReasoningEffort.MAX),
                new NoOpAuditStore(),
                new NoOpBudgetStore(),
                new RecordingEventPublisher(),
                idGenerator,
                CLOCK);
        CompressionOutputParser parser = output -> new CompressionContent(
                output,
                List.of(output),
                List.of(),
                List.of(),
                List.of("b0", "untrusted-model-reference"));
        try (var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            var service = new CompressionApplicationService(
                    repository,
                    store(context),
                    new AiExecutionPolicyExecutor(invoker, CLOCK),
                    parser,
                    idGenerator,
                    CLOCK,
                    executor);
            return service.compress(RUN_ID).toCompletableFuture().join();
        }
    }

    private static long count(
            RecordingCompressionRepository repository,
            EvidenceAiReviewStage stage,
            CompressionStatus status) {
        return repository.reviews().stream()
                .filter(review -> review.stage() == stage && review.status() == status)
                .count();
    }

    private static WorkflowExecutionStore store(WorkflowExecutionContext context) {
        return (WorkflowExecutionStore) Proxy.newProxyInstance(
                WorkflowExecutionStore.class.getClassLoader(),
                new Class<?>[] {WorkflowExecutionStore.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("load")) {
                        return Optional.of(context);
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(context, arguments);
                    }
                    throw new AssertionError("Unexpected workflow store call: " + method.getName());
                });
    }

    private static NormalizedDocument document() {
        return new NormalizedDocument(
                new DocumentId("document_consensus"),
                new EvidenceId("evidence_consensus"),
                new SourceId("source_consensus"),
                SourceTier.T1,
                "market",
                new BigDecimal("0.90"),
                URI.create("https://example.com/evidence"),
                "Evidence title",
                "evidence title",
                "zh",
                "原始规范化证据包含价格、时效、反例和风险边界。",
                List.of(new ContentBlock(
                        "b0",
                        "PARAGRAPH",
                        "原始规范化证据包含价格、时效、反例和风险边界。",
                        0,
                        java.util.Map.of("tag", "p"))),
                "a".repeat(64),
                List.of("BTCUSDT"),
                NOW,
                NOW,
                NOW);
    }

    private static WorkflowDefinitionVersion version() {
        var input = node("node_input", WorkflowNodeType.INPUT, null);
        var collector = node("node_collector", WorkflowNodeType.COLLECTOR, "collect_enabled_sources");
        var cleaner = node("node_cleaner", WorkflowNodeType.CLEANER, "normalize_and_deduplicate");
        var cleanerA = node("node_cleaner_ai_a", WorkflowNodeType.AI_CLEANER, "clean_candidate");
        var cleanerB = node("node_cleaner_ai_b", WorkflowNodeType.AI_CLEANER, "clean_candidate");
        var compressorA = node("node_compressor_a", WorkflowNodeType.COMPRESSOR, "compress_candidate");
        var compressorB = node("node_compressor_b", WorkflowNodeType.COMPRESSOR, "compress_candidate");
        var validator = node("node_validator", WorkflowNodeType.COMPRESSION_VALIDATOR, "validate_consensus");
        var output = node("node_output", WorkflowNodeType.OUTPUT, "research_output");
        return new WorkflowDefinitionVersion(
                new WorkflowVersionId("workflowversion_consensus_test"),
                new WorkflowDefinitionId("workflow_consensus_test"),
                1,
                WorkflowVersionStatus.PUBLISHED,
                3,
                100,
                Duration.ofHours(1),
                1_000_000,
                new BigDecimal("100"),
                WorkflowFailurePolicy.STOP,
                "c".repeat(64),
                NOW,
                NOW,
                "test",
                List.of(input, collector, cleaner, cleanerA, cleanerB, compressorA, compressorB, validator, output),
                List.of(
                        edge("edge_input_collector", input, collector),
                        edge("edge_collector_cleaner", collector, cleaner),
                        edge("edge_cleaner_ai_a", cleaner, cleanerA),
                        edge("edge_cleaner_ai_b", cleaner, cleanerB),
                        edge("edge_ai_a_compressor_a", cleanerA, compressorA),
                        edge("edge_ai_b_compressor_a", cleanerB, compressorA),
                        edge("edge_ai_a_compressor_b", cleanerA, compressorB),
                        edge("edge_ai_b_compressor_b", cleanerB, compressorB),
                        edge("edge_compressor_a_validator", compressorA, validator),
                        edge("edge_compressor_b_validator", compressorB, validator),
                        edge("edge_validator_output", validator, output)));
    }

    private static WorkflowNodeDefinition node(
            String id,
            WorkflowNodeType type,
            String operation) {
        var llm = type.llmBacked();
        return new WorkflowNodeDefinition(
                new WorkflowNodeId(id),
                type,
                id,
                llm ? id : null,
                null,
                llm ? new AiModelBinding(
                        new AiProviderProfileId("provider_test_default"),
                        "model-test",
                        ReasoningEffort.MAX) : null,
                null,
                llm ? "Return a structured evidence review without hidden reasoning." : null,
                llm ? "Review the supplied evidence." : null,
                llm ? WorkflowOutputContract.RESEARCH_FINDINGS : null,
                type == WorkflowNodeType.INPUT ? WorkflowContextMode.NONE : WorkflowContextMode.UPSTREAM,
                0,
                llm ? 24 : 0,
                512,
                30,
                new WorkflowRetryPolicy(1, Duration.ZERO),
                type == WorkflowNodeType.INPUT ? "research_input" : operation,
                new WorkflowCanvasPosition(BigDecimal.ZERO, BigDecimal.ZERO),
                true);
    }

    private static WorkflowEdgeDefinition edge(
            String id,
            WorkflowNodeDefinition source,
            WorkflowNodeDefinition target) {
        return new WorkflowEdgeDefinition(
                new WorkflowEdgeId(id),
                source.nodeId(),
                target.nodeId(),
                WorkflowActivationMode.ALL,
                WorkflowEdgeContextMode.INCLUDE,
                null,
                false,
                null);
    }

    private static final class RecordingCompressionRepository implements CompressionRepository {
        private final NormalizedDocument document;
        private final List<EvidenceAiReview> reviews = new CopyOnWriteArrayList<>();
        private final List<AiCompressionRecord> compressions = new CopyOnWriteArrayList<>();
        private final List<CompressionPackage> packages = new CopyOnWriteArrayList<>();

        private RecordingCompressionRepository(NormalizedDocument document) {
            this.document = document;
        }

        @Override
        public List<NormalizedDocument> listWorkflowDocuments(WorkflowRunId workflowRunId, int limit) {
            return List.of(document);
        }

        @Override
        public void saveCompression(AiCompressionRecord compression) {
            compressions.add(compression);
        }

        @Override
        public void saveEvidenceReview(EvidenceAiReview review) {
            reviews.add(review);
        }

        @Override
        public void saveCompressionPackage(CompressionPackage compressionPackage, String contentHash) {
            packages.add(compressionPackage);
        }

        private List<EvidenceAiReview> reviews() {
            return List.copyOf(reviews);
        }

        private List<AiCompressionRecord> compressions() {
            return List.copyOf(compressions);
        }
    }

    private static final class RecordingGateway implements AiCompletionGateway {
        private final Set<WorkflowNodeId> failingNodes;
        private final List<AiCompletionRequest> requests = new CopyOnWriteArrayList<>();
        private final java.util.concurrent.ConcurrentMap<WorkflowNodeId, AtomicInteger> calls =
                new java.util.concurrent.ConcurrentHashMap<>();

        private RecordingGateway(Set<WorkflowNodeId> failingNodes) {
            this.failingNodes = Set.copyOf(failingNodes);
        }

        @Override
        public Flow.Publisher<AiCompletionEvent> stream(AiCompletionRequest request) {
            requests.add(request);
            var call = calls.computeIfAbsent(request.nodeId(), ignored -> new AtomicInteger())
                    .incrementAndGet();
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean emitted;

                @Override
                public void request(long count) {
                    if (emitted || count <= 0) {
                        return;
                    }
                    emitted = true;
                    subscriber.onNext(new AiStreamStarted(request.invocationId(), NOW));
                    if (failingNodes.contains(request.nodeId())) {
                        subscriber.onNext(new AiCompletionFailed(
                                request.invocationId(),
                                "AI_PROVIDER_UNAVAILABLE",
                                "AI provider unavailable",
                                false,
                                NOW));
                    } else {
                        subscriber.onNext(new AiTextDelta(
                                request.invocationId(),
                                1,
                                request.nodeId().value() + "-call-" + call,
                                NOW));
                        subscriber.onNext(new AiUsageReported(request.invocationId(), 10, 5, NOW));
                        subscriber.onNext(new AiCompletionFinished(request.invocationId(), "stop", NOW));
                    }
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                    emitted = true;
                }
            });
        }

        private List<AiCompletionRequest> requests() {
            return List.copyOf(requests);
        }
    }

    private static final class NoOpAuditStore implements AiInvocationAuditStore {
        @Override
        public void start(AiInvocationStart start) {
        }

        @Override
        public void appendChunk(AiInvocationId invocationId, long sequence, String content, Instant occurredAt) {
        }

        @Override
        public void complete(AiInvocationCompletion completion) {
        }

        @Override
        public void fail(AiInvocationFailure failure) {
        }
    }

    private static final class NoOpBudgetStore implements AiBudgetReservationStore {
        @Override
        public void reserve(
                AiInvocationId invocationId,
                WorkflowRunId runId,
                AiProviderProfileId providerProfileId,
                String modelName,
                long estimatedInputTokens,
                long maximumOutputTokens,
                long maximumWorkflowTokens,
                BigDecimal maximumWorkflowCostUsd,
                Instant reservedAt) {
        }
    }

    private static final class RecordingEventPublisher implements WorkflowEventPublisher {
        private final AtomicLong sequence = new AtomicLong();
        private final List<WorkflowEvent> events = new ArrayList<>();

        @Override
        public synchronized WorkflowEvent publish(WorkflowRunId runId, WorkflowEventFactory factory) {
            var next = sequence.incrementAndGet();
            var event = factory.create(
                    new WorkflowEventId("event_consensus" + String.format("%04d", next)),
                    next,
                    NOW);
            events.add(event);
            return event;
        }
    }
}
