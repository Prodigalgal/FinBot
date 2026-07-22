package io.omnnu.finbot.application.workflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.application.ai.dto.AiCompletionEvent;
import io.omnnu.finbot.application.ai.dto.AiCompletionFinished;
import io.omnnu.finbot.application.ai.dto.AiCompletionRequest;
import io.omnnu.finbot.application.ai.dto.AiInvocationCompletion;
import io.omnnu.finbot.application.ai.dto.AiInvocationFailure;
import io.omnnu.finbot.application.ai.dto.AiInvocationStart;
import io.omnnu.finbot.application.ai.dto.AiRuntimeBinding;
import io.omnnu.finbot.application.ai.dto.AiStreamStarted;
import io.omnnu.finbot.application.ai.dto.AiTextDelta;
import io.omnnu.finbot.application.ai.dto.AiUsageReported;
import io.omnnu.finbot.application.ai.port.out.AiBudgetReservationStore;
import io.omnnu.finbot.application.ai.port.out.AiCompletionGateway;
import io.omnnu.finbot.application.ai.port.out.AiInvocationAuditStore;
import io.omnnu.finbot.application.ai.service.AiExecutionPolicyExecutor;
import io.omnnu.finbot.application.ai.service.WorkflowAiInvoker;
import io.omnnu.finbot.application.workflow.dto.DebateSession;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.application.workflow.port.out.DebateProtocolStore;
import io.omnnu.finbot.application.workflow.port.out.WorkflowEventFactory;
import io.omnnu.finbot.application.workflow.port.out.WorkflowEventPublisher;
import io.omnnu.finbot.domain.ai.AiInvocationId;
import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.consensus.ConsensusBallot;
import io.omnnu.finbot.domain.consensus.ConsensusDecision;
import io.omnnu.finbot.domain.consensus.LogicalRoleKey;
import io.omnnu.finbot.domain.debate.CritiqueAssignmentPolicy;
import io.omnnu.finbot.domain.debate.DebateArtifact;
import io.omnnu.finbot.domain.debate.DebateArtifactId;
import io.omnnu.finbot.domain.debate.DebateArtifactStatus;
import io.omnnu.finbot.domain.debate.DebateCandidate;
import io.omnnu.finbot.domain.debate.DebateCandidateId;
import io.omnnu.finbot.domain.debate.DebatePhase;
import io.omnnu.finbot.domain.debate.DebatePhaseId;
import io.omnnu.finbot.domain.debate.DebatePhaseStatus;
import io.omnnu.finbot.domain.debate.DebatePhaseType;
import io.omnnu.finbot.domain.debate.DebateProtocol;
import io.omnnu.finbot.domain.debate.DebateProtocolConfiguration;
import io.omnnu.finbot.domain.debate.DebateTask;
import io.omnnu.finbot.domain.debate.DebateTaskId;
import io.omnnu.finbot.domain.debate.DebateTaskStatus;
import io.omnnu.finbot.domain.debate.DebateTaskVariant;
import io.omnnu.finbot.domain.workflow.DebateId;
import io.omnnu.finbot.domain.workflow.DebateStatus;
import io.omnnu.finbot.domain.workflow.WorkflowActivationMode;
import io.omnnu.finbot.domain.workflow.WorkflowCanvasPosition;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SdbScaPhaseExecutorTest {
    private static final Instant NOW = Instant.parse("2026-07-22T14:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final WorkflowRunId RUN_ID = new WorkflowRunId("run_sdb_phase_test");

    @Test
    void revealsOnlyAfterTheBarrierAndReplaysWithoutCallingAiAgain() {
        var version = version();
        var execution = new WorkflowExecutionContext(
                RUN_ID,
                WorkflowRunStatus.RUNNING,
                "Analyze an anonymous market snapshot",
                "{\"snapshot\":\"frozen\"}",
                version);
        var session = new DebateSession(
                new DebateId("debate_sdb_phase_test"),
                RUN_ID,
                DebateStatus.RUNNING,
                1,
                0,
                new WorkflowNodeId("node_social_choice"),
                NOW,
                null);
        var protocolStore = new InMemoryProtocolStore();
        var completionGateway = new RecordingCompletionGateway();
        var ids = new AtomicInteger();
        var invoker = new WorkflowAiInvoker(
                completionGateway,
                ignored -> new AiRuntimeBinding(AiProtocol.RESPONSES, ReasoningEffort.MAX),
                new NoOpAuditStore(),
                new NoOpBudgetStore(),
                new RecordingEventPublisher(),
                prefix -> prefix + "sdbphase" + ids.incrementAndGet(),
                CLOCK);
        var phaseExecutor = new SdbScaPhaseExecutor(
                protocolStore,
                new AiExecutionPolicyExecutor(invoker, CLOCK),
                CLOCK,
                Runnable::run);
        var commands = version.nodes().stream()
                .filter(node -> node.nodeType() == WorkflowNodeType.AGENT)
                .map(node -> new SdbScaPhaseExecutor.TaskCommand(
                        node,
                        null,
                        DebateTaskVariant.PRIMARY,
                        "Generate an independent proposal from the frozen snapshot.",
                        output -> "{\"proposal\":\"" + output + "\"}"))
                .toList();

        var first = phaseExecutor.execute(
                execution,
                session,
                1,
                DebatePhaseType.PROPOSAL,
                commands);

        assertEquals(2, completionGateway.requests().size());
        assertEquals(2, first.tasks().size());
        assertTrue(first.tasks().stream().allMatch(task -> task.status() == DebateTaskStatus.COMPLETED));
        assertTrue(first.artifacts().stream()
                .allMatch(artifact -> artifact.status() == DebateArtifactStatus.REVEALED));
        assertFalse(protocolStore.prematureRevealAttempt());

        var replay = phaseExecutor.execute(
                execution,
                session,
                1,
                DebatePhaseType.PROPOSAL,
                commands);

        assertEquals(2, completionGateway.requests().size());
        assertEquals(first.artifacts(), replay.artifacts());
        assertFalse(replay.partial());
    }

    private static WorkflowDefinitionVersion version() {
        var input = deterministicNode("node_input", WorkflowNodeType.INPUT, null, null);
        var agentA = agent("node_agent_alpha", "macro_role");
        var agentB = agent("node_agent_beta", "risk_role");
        var decision = deterministicNode(
                "node_social_choice",
                WorkflowNodeType.SOCIAL_CHOICE,
                WorkflowOutputContract.CONSENSUS_RESULT,
                "schulze_social_choice");
        var output = deterministicNode("node_output", WorkflowNodeType.OUTPUT, null, null);
        return new WorkflowDefinitionVersion(
                new WorkflowVersionId("workflowversion_sdb_phase_test"),
                new WorkflowDefinitionId("workflow_sdb_phase_test"),
                1,
                WorkflowVersionStatus.PUBLISHED,
                1,
                new DebateProtocolConfiguration(
                        DebateProtocol.SDB_SCA_V1,
                        2,
                        2,
                        Duration.ofMinutes(5),
                        CritiqueAssignmentPolicy.FULL_MATRIX),
                20,
                Duration.ofMinutes(10),
                100_000,
                BigDecimal.TEN,
                WorkflowFailurePolicy.STOP,
                "a".repeat(64),
                NOW,
                NOW,
                "test",
                List.of(input, agentA, agentB, decision, output),
                List.of(
                        edge("edge_input_alpha", input, agentA, WorkflowEdgeContextMode.INCLUDE),
                        edge("edge_input_beta", input, agentB, WorkflowEdgeContextMode.INCLUDE),
                        edge("edge_alpha_choice", agentA, decision, WorkflowEdgeContextMode.EXCLUDE),
                        edge("edge_beta_choice", agentB, decision, WorkflowEdgeContextMode.EXCLUDE),
                        edge("edge_choice_output", decision, output, WorkflowEdgeContextMode.INCLUDE)));
    }

    private static WorkflowNodeDefinition agent(String id, String logicalRoleKey) {
        return new WorkflowNodeDefinition(
                new WorkflowNodeId(id),
                WorkflowNodeType.AGENT,
                id,
                logicalRoleKey,
                null,
                new LogicalRoleKey(logicalRoleKey),
                new AiModelBinding(
                        new AiProviderProfileId("provider_sdb_test"),
                        "model-sdb-test",
                        ReasoningEffort.MAX),
                null,
                "Return one independent structured proposal.",
                "Use only the frozen research snapshot.",
                WorkflowOutputContract.DEBATE_ARGUMENT,
                WorkflowContextMode.UPSTREAM,
                0,
                8,
                256,
                30,
                new WorkflowRetryPolicy(1, Duration.ZERO),
                null,
                new WorkflowCanvasPosition(BigDecimal.ZERO, BigDecimal.ZERO),
                true);
    }

    private static WorkflowNodeDefinition deterministicNode(
            String id,
            WorkflowNodeType type,
            WorkflowOutputContract outputContract,
            String operation) {
        return new WorkflowNodeDefinition(
                new WorkflowNodeId(id),
                type,
                id,
                null,
                null,
                null,
                null,
                null,
                null,
                outputContract,
                WorkflowContextMode.NONE,
                0,
                0,
                64,
                30,
                new WorkflowRetryPolicy(1, Duration.ZERO),
                operation,
                new WorkflowCanvasPosition(BigDecimal.ZERO, BigDecimal.ZERO),
                true);
    }

    private static WorkflowEdgeDefinition edge(
            String id,
            WorkflowNodeDefinition source,
            WorkflowNodeDefinition target,
            WorkflowEdgeContextMode contextMode) {
        return new WorkflowEdgeDefinition(
                new WorkflowEdgeId(id),
                source.nodeId(),
                target.nodeId(),
                WorkflowActivationMode.ALL,
                contextMode,
                (WorkflowCondition) null,
                false,
                null);
    }

    private static final class RecordingCompletionGateway implements AiCompletionGateway {
        private final List<AiCompletionRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public Flow.Publisher<AiCompletionEvent> stream(AiCompletionRequest request) {
            requests.add(request);
            var output = "proposal-" + requests.size();
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean emitted;

                @Override
                public void request(long count) {
                    if (emitted || count <= 0) {
                        return;
                    }
                    emitted = true;
                    subscriber.onNext(new AiStreamStarted(request.invocationId(), NOW));
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
        private final AtomicLong sequence = new AtomicLong();

        @Override
        public WorkflowEvent publish(WorkflowRunId runId, WorkflowEventFactory factory) {
            var next = sequence.incrementAndGet();
            return factory.create(new WorkflowEventId("event_sdb_phase_" + next), next, NOW);
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

    private static final class InMemoryProtocolStore implements DebateProtocolStore {
        private DebatePhase phase;
        private final Map<DebateTaskId, DebateTask> tasks = new LinkedHashMap<>();
        private final Map<DebateTaskId, DebateArtifact> artifacts = new LinkedHashMap<>();
        private boolean prematureRevealAttempt;

        @Override
        public synchronized void createPhase(DebatePhase proposed, List<DebateTask> proposedTasks) {
            if (phase != null) {
                return;
            }
            phase = proposed;
            proposedTasks.forEach(task -> tasks.put(task.taskId(), task));
        }

        @Override
        public synchronized Optional<DebatePhase> phase(DebatePhaseId phaseId) {
            return phase != null && phase.phaseId().equals(phaseId) ? Optional.of(phase) : Optional.empty();
        }

        @Override
        public synchronized Optional<DebatePhase> currentPhase(DebateId debateId) {
            return phase != null && phase.debateId().equals(debateId) ? Optional.of(phase) : Optional.empty();
        }

        @Override
        public synchronized List<DebateTask> claimTasks(
                DebatePhaseId phaseId,
                String leaseOwner,
                int maximumTasks,
                Duration leaseDuration,
                Instant now) {
            if (phase.status() != DebatePhaseStatus.OPEN) {
                return List.of();
            }
            var claimed = new ArrayList<DebateTask>();
            for (var task : tasks.values()) {
                if (task.status() != DebateTaskStatus.PENDING || claimed.size() == maximumTasks) {
                    continue;
                }
                var leased = new DebateTask(
                        task.taskId(), task.phaseId(), task.actorNodeId(), task.logicalRoleKey(),
                        task.targetCandidateId(), task.variant(), task.inputHash(), DebateTaskStatus.CLAIMED,
                        task.attempt() + 1, leaseOwner, now.plus(leaseDuration), task.createdAt(), null);
                tasks.put(leased.taskId(), leased);
                claimed.add(leased);
            }
            return List.copyOf(claimed);
        }

        @Override
        public synchronized void sealArtifact(DebateArtifact artifact, String leaseOwner, Instant completedAt) {
            var task = tasks.get(artifact.taskId());
            assertEquals(DebateTaskStatus.CLAIMED, task.status());
            assertEquals(leaseOwner, task.leaseOwner());
            artifacts.put(task.taskId(), artifact);
            terminalize(task, DebateTaskStatus.COMPLETED, completedAt);
        }

        @Override
        public synchronized void failTask(
                DebateTaskId taskId,
                String leaseOwner,
                String errorCode,
                String errorMessage,
                Instant completedAt) {
            var task = tasks.get(taskId);
            assertEquals(leaseOwner, task.leaseOwner());
            terminalize(task, DebateTaskStatus.FAILED, completedAt);
        }

        @Override
        public synchronized void timeoutTask(DebateTaskId taskId, Instant completedAt) {
            terminalize(tasks.get(taskId), DebateTaskStatus.TIMED_OUT, completedAt);
        }

        @Override
        public synchronized boolean revealPhase(DebatePhaseId phaseId, long expectedVersion, Instant revealedAt) {
            if (phase.status() == DebatePhaseStatus.REVEALED) {
                return false;
            }
            if (!phase.barrierSatisfied()) {
                prematureRevealAttempt = true;
                return false;
            }
            artifacts.replaceAll((taskId, artifact) -> new DebateArtifact(
                    artifact.artifactId(), artifact.taskId(), artifact.phaseId(), DebateArtifactStatus.REVEALED,
                    artifact.contentHash(), artifact.content(), artifact.sealedAt(), revealedAt));
            phase = new DebatePhase(
                    phase.phaseId(), phase.debateId(), phase.protocol(), phase.generation(), phase.phaseType(),
                    DebatePhaseStatus.REVEALED, phase.requiredTasks(), phase.completedTasks(), phase.deadline(),
                    phase.openedAt(), revealedAt, null, phase.version() + 1);
            return true;
        }

        @Override
        public synchronized List<DebateTask> tasks(DebatePhaseId phaseId) {
            return List.copyOf(tasks.values());
        }

        @Override
        public synchronized List<DebateArtifact> revealedArtifacts(DebatePhaseId phaseId) {
            return artifacts.values().stream()
                    .filter(artifact -> artifact.status() == DebateArtifactStatus.REVEALED)
                    .toList();
        }

        @Override
        public void saveCandidates(List<DebateCandidate> candidates) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DebateCandidate> candidates(DebateId debateId) {
            return List.of();
        }

        @Override
        public void attachRevision(DebateCandidateId candidateId, DebateArtifactId revisionArtifactId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void saveBallots(List<ConsensusBallot> ballots) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ConsensusBallot> ballots(DebateId debateId) {
            return List.of();
        }

        @Override
        public void saveDecision(ConsensusDecision decision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ConsensusDecision> decision(DebateId debateId) {
            return Optional.empty();
        }

        private void terminalize(DebateTask task, DebateTaskStatus status, Instant completedAt) {
            tasks.put(task.taskId(), new DebateTask(
                    task.taskId(), task.phaseId(), task.actorNodeId(), task.logicalRoleKey(),
                    task.targetCandidateId(), task.variant(), task.inputHash(), status,
                    task.attempt(), null, null, task.createdAt(), completedAt));
            phase = new DebatePhase(
                    phase.phaseId(), phase.debateId(), phase.protocol(), phase.generation(), phase.phaseType(),
                    phase.status(), phase.requiredTasks(), phase.completedTasks() + 1, phase.deadline(),
                    phase.openedAt(), phase.revealedAt(), phase.completedAt(), phase.version() + 1);
        }

        private synchronized boolean prematureRevealAttempt() {
            return prematureRevealAttempt;
        }
    }
}
