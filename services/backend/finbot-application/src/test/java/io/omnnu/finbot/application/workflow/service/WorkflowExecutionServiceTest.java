package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.application.workflow.dto.DebateSession;
import io.omnnu.finbot.application.workflow.dto.WorkflowCheckpoint;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.application.workflow.port.out.StructuredAiOutputParser;
import io.omnnu.finbot.application.workflow.port.out.WorkflowEventFactory;
import io.omnnu.finbot.application.workflow.port.out.WorkflowEventPublisher;
import io.omnnu.finbot.application.workflow.port.out.WorkflowExecutionStore;
import io.omnnu.finbot.application.workflow.service.WorkflowExecutionService;
import io.omnnu.finbot.application.workflow.service.WorkflowRunFailureService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.application.ai.port.out.AiBudgetReservationStore;
import io.omnnu.finbot.application.ai.dto.AiCompletionEvent;
import io.omnnu.finbot.application.ai.dto.AiCompletionFinished;
import io.omnnu.finbot.application.ai.dto.AiCompletionFailed;
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
import io.omnnu.finbot.application.shared.port.out.SortableIdGenerator;
import io.omnnu.finbot.domain.ai.AiInvocationId;
import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.workflow.AgentMessage;
import io.omnnu.finbot.domain.workflow.AgentMessageContent;
import io.omnnu.finbot.domain.workflow.AgentMessageStatus;
import io.omnnu.finbot.domain.workflow.DebateId;
import io.omnnu.finbot.domain.workflow.DebateStatus;
import io.omnnu.finbot.domain.workflow.WorkflowActivationMode;
import io.omnnu.finbot.domain.workflow.WorkflowCanvasPosition;
import io.omnnu.finbot.domain.workflow.WorkflowCheckpointId;
import io.omnnu.finbot.domain.workflow.WorkflowCheckpointStatus;
import io.omnnu.finbot.domain.workflow.WorkflowCondition;
import io.omnnu.finbot.domain.workflow.WorkflowContextMode;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionId;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeContextMode;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeId;
import io.omnnu.finbot.domain.workflow.WorkflowEvent;
import io.omnnu.finbot.domain.workflow.WorkflowEventId;
import io.omnnu.finbot.domain.workflow.WorkflowFailurePolicy;
import io.omnnu.finbot.domain.workflow.WorkflowFailed;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import io.omnnu.finbot.domain.workflow.WorkflowOutputContract;
import io.omnnu.finbot.domain.workflow.WorkflowRetryPolicy;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import io.omnnu.finbot.domain.workflow.WorkflowVersionStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WorkflowExecutionServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-14T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final WorkflowRunId RUN_ID = new WorkflowRunId("run_test0001");

    @Test
    void executesThreeRealRoundsThenIndependentChairVerdict() {
        var version = workflowVersion();
        var store = new InMemoryExecutionStore(new WorkflowExecutionContext(
                RUN_ID,
                WorkflowRunStatus.ACCEPTED,
                "Analyze BTC liquidity and directional risk",
                "{\"evidence\":[{\"evidence_id\":\"evidence_test\"}]}",
                version));
        var gateway = new RecordingCompletionGateway();
        var events = new RecordingEventPublisher();
        var idSequence = new AtomicInteger();
        SortableIdGenerator ids = prefix -> prefix + "test0000" + idSequence.incrementAndGet();
        var invoker = new WorkflowAiInvoker(
                gateway,
                ignored -> new AiRuntimeBinding(AiProtocol.CHAT, ReasoningEffort.MAX),
                new NoOpAuditStore(),
                new NoOpBudgetStore(),
                events,
                ids,
                CLOCK);
        try (var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            var service = new WorkflowExecutionService(
                    store,
                    events,
                    new WorkflowRunFailureService(store, events),
                    new AiExecutionPolicyExecutor(invoker, CLOCK),
                    outputParser(),
                    CLOCK,
                    executor);

            service.execute(RUN_ID).toCompletableFuture().join();
        }

        assertEquals(WorkflowRunStatus.COMPLETED, store.status());
        assertEquals(7, gateway.requests().size());
        assertEquals(7, store.messages().size());
        assertEquals(3, store.debate().orElseThrow().completedRounds());
        assertEquals(DebateStatus.COMPLETED, store.debate().orElseThrow().status());

        var agentARequests = gateway.requests().stream()
                .filter(request -> request.nodeId().value().equals("node_agent_a"))
                .toList();
        assertEquals(3, agentARequests.size());
        assertFalse(agentARequests.getFirst().userPrompt().contains("node_agent_b-call-1"));
        assertTrue(agentARequests.get(1).userPrompt().contains("node_agent_b-call-1"));
        assertTrue(agentARequests.get(2).userPrompt().contains("node_agent_b-call-2"));

        var agentARoundTwo = store.messages().stream()
                .filter(message -> message.nodeId().value().equals("node_agent_a"))
                .filter(message -> message.roundIndex() == 2)
                .findFirst()
                .orElseThrow();
        assertEquals(2, agentARoundTwo.repliesTo().size());
        assertTrue(store.messages().stream()
                .filter(message -> message.nodeId().value().equals("node_chair00"))
                .allMatch(message -> message.roundIndex() == 0));
        assertTrue(events.events().stream()
                .map(WorkflowEvent::eventType)
                .anyMatch("workflow.ai.text.delta"::equals));
    }

    @Test
    void chairFailureTerminalizesTheWorkflowAndDebate() {
        var version = workflowVersion();
        var store = new InMemoryExecutionStore(new WorkflowExecutionContext(
                RUN_ID,
                WorkflowRunStatus.ACCEPTED,
                "Analyze BTC liquidity and directional risk",
                "{\"evidence\":[{\"evidence_id\":\"evidence_test\"}]}",
                version));
        var gateway = new RecordingCompletionGateway(new WorkflowNodeId("node_chair00"));
        var events = new RecordingEventPublisher();
        var idSequence = new AtomicInteger();
        SortableIdGenerator ids = prefix -> prefix + "test0000" + idSequence.incrementAndGet();
        var invoker = new WorkflowAiInvoker(
                gateway,
                ignored -> new AiRuntimeBinding(AiProtocol.CHAT, ReasoningEffort.MAX),
                new NoOpAuditStore(),
                new NoOpBudgetStore(),
                events,
                ids,
                CLOCK);

        try (var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            var service = new WorkflowExecutionService(
                    store,
                    events,
                    new WorkflowRunFailureService(store, events),
                    new AiExecutionPolicyExecutor(invoker, CLOCK),
                    outputParser(),
                    CLOCK,
                    executor);

            assertThrows(
                    CompletionException.class,
                    () -> service.execute(RUN_ID).toCompletableFuture().join());
        }

        assertEquals(WorkflowRunStatus.FAILED, store.status());
        assertEquals(DebateStatus.FAILED, store.debate().orElseThrow().status());
        assertEquals(
                WorkflowCheckpointStatus.FAILED,
                store.checkpoint(new WorkflowNodeId("node_chair00"), 0).orElseThrow().status());
        var failed = events.events().stream()
                .filter(WorkflowFailed.class::isInstance)
                .map(WorkflowFailed.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("AI_PROVIDER_TEMPORARILY_UNAVAILABLE", failed.errorCode());
        assertTrue(failed.retryable());
    }

    @Test
    void exhaustsPrimaryBindingBeforeUsingConfiguredFallback() {
        var version = workflowVersion();
        var store = new InMemoryExecutionStore(new WorkflowExecutionContext(
                RUN_ID,
                WorkflowRunStatus.ACCEPTED,
                "Analyze BTC liquidity and directional risk",
                "{\"evidence\":[{\"evidence_id\":\"evidence_test\"}]}",
                version));
        var gateway = new RecordingCompletionGateway(
                new WorkflowNodeId("node_chair00"),
                new AiProviderProfileId("provider_test_default"));
        var events = new RecordingEventPublisher();
        var idSequence = new AtomicInteger();
        SortableIdGenerator ids = prefix -> prefix + "fallback" + idSequence.incrementAndGet();
        var invoker = new WorkflowAiInvoker(
                gateway,
                ignored -> new AiRuntimeBinding(AiProtocol.CHAT, ReasoningEffort.MAX),
                new NoOpAuditStore(),
                new NoOpBudgetStore(),
                events,
                ids,
                CLOCK);

        try (var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            var service = new WorkflowExecutionService(
                    store,
                    events,
                    new WorkflowRunFailureService(store, events),
                    new AiExecutionPolicyExecutor(invoker, CLOCK),
                    outputParser(),
                    CLOCK,
                    executor);
            service.execute(RUN_ID).toCompletableFuture().join();
        }

        var chairRequests = gateway.requests().stream()
                .filter(request -> request.nodeId().value().equals("node_chair00"))
                .toList();
        assertEquals(2, chairRequests.size());
        assertEquals("provider_test_default", chairRequests.get(0).providerProfileId().value());
        assertEquals("provider_fallback_default", chairRequests.get(1).providerProfileId().value());
        assertEquals(WorkflowRunStatus.COMPLETED, store.status());
    }

    private static StructuredAiOutputParser outputParser() {
        return new StructuredAiOutputParser() {
            @Override
            public AgentMessageContent parseAgent(String output) {
                return content(output);
            }

            @Override
            public AgentMessageContent parseChair(String output) {
                return content("chair-" + output);
            }
        };
    }

    private static AgentMessageContent content(String output) {
        return new AgentMessageContent(
                output,
                "Auditable argument for " + output,
                new BigDecimal("0.70"),
                List.of(),
                List.of("evidence:test"),
                List.of(),
                List.of());
    }

    private static WorkflowDefinitionVersion workflowVersion() {
        var input = node("node_input000", WorkflowNodeType.INPUT, WorkflowContextMode.NONE);
        var agentA = node("node_agent_a", WorkflowNodeType.AGENT, WorkflowContextMode.LATEST);
        var agentB = node("node_agent_b", WorkflowNodeType.AGENT, WorkflowContextMode.LATEST);
        var chair = node("node_chair00", WorkflowNodeType.CHAIR, WorkflowContextMode.UPSTREAM, true);
        var output = node("node_output00", WorkflowNodeType.OUTPUT, WorkflowContextMode.UPSTREAM);
        return new WorkflowDefinitionVersion(
                new WorkflowVersionId("workflowversion_test_v1"),
                new WorkflowDefinitionId("workflow_test_definition"),
                1,
                WorkflowVersionStatus.PUBLISHED,
                3,
                20,
                Duration.ofMinutes(10),
                100_000,
                BigDecimal.TEN,
                WorkflowFailurePolicy.STOP,
                "a".repeat(64),
                NOW,
                NOW,
                "test",
                List.of(input, agentA, agentB, chair, output),
                List.of(
                        edge("edge_input_a", input, agentA),
                        edge("edge_input_b", input, agentB),
                        edge("edge_a_chair", agentA, chair),
                        edge("edge_b_chair", agentB, chair),
                        edge("edge_chair_out", chair, output)));
    }

    private static WorkflowNodeDefinition node(
            String id,
            WorkflowNodeType type,
            WorkflowContextMode contextMode) {
        return node(id, type, contextMode, false);
    }

    private static WorkflowNodeDefinition node(
            String id,
            WorkflowNodeType type,
            WorkflowContextMode contextMode,
            boolean fallback) {
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
                        ReasoningEffort.HIGH) : null,
                llm && fallback ? new AiModelBinding(
                        new AiProviderProfileId("provider_fallback_default"),
                        "model-fallback",
                        ReasoningEffort.MEDIUM) : null,
                llm ? "Return a structured, evidence-backed result." : null,
                llm ? "Review the request and the supplied context." : null,
                llm
                        ? (type == WorkflowNodeType.CHAIR
                                ? WorkflowOutputContract.CHAIR_VERDICT
                                : WorkflowOutputContract.DEBATE_ARGUMENT)
                        : null,
                contextMode,
                3,
                16,
                128,
                30,
                new WorkflowRetryPolicy(1, Duration.ZERO),
                null,
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
                (WorkflowCondition) null,
                false,
                null);
    }

    private static final class RecordingCompletionGateway implements AiCompletionGateway {
        private final List<AiCompletionRequest> requests = new CopyOnWriteArrayList<>();
        private final Map<WorkflowNodeId, AtomicInteger> calls = new ConcurrentHashMap<>();
        private final WorkflowNodeId failingNodeId;
        private final AiProviderProfileId failingProviderProfileId;

        private RecordingCompletionGateway() {
            this(null, null);
        }

        private RecordingCompletionGateway(WorkflowNodeId failingNodeId) {
            this(failingNodeId, null);
        }

        private RecordingCompletionGateway(
                WorkflowNodeId failingNodeId,
                AiProviderProfileId failingProviderProfileId) {
            this.failingNodeId = failingNodeId;
            this.failingProviderProfileId = failingProviderProfileId;
        }

        @Override
        public Flow.Publisher<AiCompletionEvent> stream(AiCompletionRequest request) {
            requests.add(request);
            var call = calls.computeIfAbsent(request.nodeId(), ignored -> new AtomicInteger())
                    .incrementAndGet();
            var output = request.nodeId().value() + "-call-" + call;
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean emitted;

                @Override
                public void request(long count) {
                    if (emitted || count <= 0) {
                        return;
                    }
                    emitted = true;
                    subscriber.onNext(new AiStreamStarted(request.invocationId(), NOW));
                    if (request.nodeId().equals(failingNodeId)
                            && (failingProviderProfileId == null
                                    || request.providerProfileId().equals(failingProviderProfileId))) {
                        subscriber.onNext(new AiCompletionFailed(
                                request.invocationId(),
                                "AI_PROVIDER_TEMPORARILY_UNAVAILABLE",
                                "AI provider is temporarily unavailable",
                                true,
                                NOW));
                        subscriber.onComplete();
                        return;
                    }
                    subscriber.onNext(new AiTextDelta(request.invocationId(), 1, output, NOW));
                    subscriber.onNext(new AiUsageReported(request.invocationId(), 10, 5, NOW));
                    subscriber.onNext(new AiCompletionFinished(request.invocationId(), "stop", NOW));
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

    private static final class RecordingEventPublisher implements WorkflowEventPublisher {
        private final AtomicLong sequence = new AtomicLong(1);
        private final List<WorkflowEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public WorkflowEvent publish(WorkflowRunId runId, WorkflowEventFactory factory) {
            var next = sequence.incrementAndGet();
            var event = factory.create(
                    new WorkflowEventId("event_test" + String.format("%04d", next)),
                    next,
                    NOW);
            events.add(event);
            return event;
        }

        private List<WorkflowEvent> events() {
            return List.copyOf(events);
        }
    }

    private static final class NoOpAuditStore implements AiInvocationAuditStore {
        @Override
        public void start(AiInvocationStart start) {
        }

        @Override
        public void appendChunk(
                AiInvocationId invocationId,
                long sequence,
                String content,
                Instant occurredAt) {
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

    private static final class InMemoryExecutionStore implements WorkflowExecutionStore {
        private final WorkflowExecutionContext initialContext;
        private final AtomicReference<WorkflowRunStatus> status =
                new AtomicReference<>(WorkflowRunStatus.ACCEPTED);
        private final AtomicReference<DebateSession> debate = new AtomicReference<>();
        private final Map<String, WorkflowCheckpoint> checkpoints = new ConcurrentHashMap<>();
        private final List<AgentMessage> messages = new CopyOnWriteArrayList<>();

        private InMemoryExecutionStore(WorkflowExecutionContext initialContext) {
            this.initialContext = initialContext;
        }

        @Override
        public Optional<WorkflowExecutionContext> load(WorkflowRunId runId) {
            return Optional.of(new WorkflowExecutionContext(
                    runId,
                    status.get(),
                    initialContext.requestSummary(),
                    initialContext.researchContext(),
                    initialContext.definitionVersion()));
        }

        @Override
        public boolean markRunning(WorkflowRunId runId, Instant startedAt) {
            status.set(WorkflowRunStatus.RUNNING);
            return true;
        }

        @Override
        public boolean resumeFailed(WorkflowRunId runId, Instant resumedAt) {
            return status.compareAndSet(WorkflowRunStatus.FAILED, WorkflowRunStatus.ACCEPTED);
        }

        @Override
        public void saveCheckpoint(WorkflowCheckpoint checkpoint) {
            checkpoints.put(checkpointKey(
                    checkpoint.nodeId(),
                    checkpoint.roundIndex(),
                    checkpoint.iteration()), checkpoint);
        }

        @Override
        public Optional<WorkflowCheckpoint> findCheckpoint(
                WorkflowRunId runId,
                WorkflowNodeId nodeId,
                int roundIndex,
                int iteration) {
            return Optional.ofNullable(checkpoints.get(checkpointKey(nodeId, roundIndex, iteration)));
        }

        @Override
        public void startDebate(DebateSession session) {
            debate.compareAndSet(null, session);
        }

        @Override
        public Optional<DebateSession> findDebate(WorkflowRunId runId) {
            return Optional.ofNullable(debate.get());
        }

        @Override
        public void updateDebate(
                DebateId debateId,
                DebateStatus debateStatus,
                int completedRounds,
                Instant completedAt) {
            debate.updateAndGet(current -> new DebateSession(
                    current.debateId(),
                    current.runId(),
                    debateStatus,
                    current.configuredRounds(),
                    completedRounds,
                    current.chairNodeId(),
                    current.startedAt(),
                    completedAt));
        }

        @Override
        public void saveMessage(AgentMessage message) {
            if (messages.stream().noneMatch(existing -> existing.messageId().equals(message.messageId()))) {
                messages.add(message);
            }
        }

        @Override
        public List<AgentMessage> messages(DebateId debateId) {
            return List.copyOf(messages);
        }

        @Override
        public void completeRun(WorkflowRunId runId, boolean partial, Instant completedAt) {
            status.set(partial ? WorkflowRunStatus.PARTIAL : WorkflowRunStatus.COMPLETED);
        }

        @Override
        public boolean failRun(WorkflowRunId runId, String errorCode, String safeMessage, Instant failedAt) {
            status.set(WorkflowRunStatus.FAILED);
            return true;
        }

        private WorkflowRunStatus status() {
            return status.get();
        }

        private Optional<DebateSession> debate() {
            return Optional.ofNullable(debate.get());
        }

        private Optional<WorkflowCheckpoint> checkpoint(WorkflowNodeId nodeId, int roundIndex) {
            return Optional.ofNullable(checkpoints.get(checkpointKey(nodeId, roundIndex, 0)));
        }

        private List<AgentMessage> messages() {
            return List.copyOf(messages);
        }

        private static String checkpointKey(WorkflowNodeId nodeId, int roundIndex, int iteration) {
            return nodeId.value() + ':' + roundIndex + ':' + iteration;
        }
    }
}
