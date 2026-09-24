package io.omnnu.finbot.application.workflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.omnnu.finbot.application.ai.service.AiExecutionPolicyExecutor;
import io.omnnu.finbot.application.workflow.dto.DebateSession;
import io.omnnu.finbot.application.workflow.dto.DecisionPanelCandidateView;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.application.workflow.port.out.DebateProtocolStore;
import io.omnnu.finbot.application.workflow.port.out.WorkflowExecutionStore;
import io.omnnu.finbot.domain.consensus.AnonymousCandidateId;
import io.omnnu.finbot.domain.consensus.AnonymousPreferenceBallot;
import io.omnnu.finbot.domain.consensus.BallotOrientation;
import io.omnnu.finbot.domain.consensus.LogicalRoleKey;
import io.omnnu.finbot.domain.consensus.SchulzeDetailedResult;
import io.omnnu.finbot.domain.consensus.SchulzeOutcome;
import io.omnnu.finbot.domain.debate.DebateArtifact;
import io.omnnu.finbot.domain.debate.DebateCandidate;
import io.omnnu.finbot.domain.debate.DebateProtocol;
import io.omnnu.finbot.domain.debate.DebateProtocolConfiguration;
import io.omnnu.finbot.domain.debate.DebateTaskVariant;
import io.omnnu.finbot.domain.debate.DecisionPanelKey;
import io.omnnu.finbot.domain.debate.DecisionPanelPurpose;
import io.omnnu.finbot.domain.workflow.DebateId;
import io.omnnu.finbot.domain.workflow.DebateStatus;
import io.omnnu.finbot.domain.workflow.WorkflowCanvasPosition;
import io.omnnu.finbot.domain.workflow.WorkflowContextMode;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionId;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowFailurePolicy;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import io.omnnu.finbot.domain.workflow.WorkflowRetryPolicy;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import io.omnnu.finbot.domain.workflow.WorkflowVersionStatus;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DecisionPanelEngineTest {
    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final Map<DecisionPanelKey, DebateSession> debates = new ConcurrentHashMap<>();
    private WorkflowExecutionStore executionStore;
    private DebateProtocolStore protocolStore;
    private DecisionPanelEngine engine;

    @BeforeEach
    void setUp() {
        debates.clear();
        executionStore = proxy(WorkflowExecutionStore.class, (proxy, method, args) -> {
            if ("startDebate".equals(method.getName())) {
                var s = (DebateSession) args[0];
                debates.put(s.panelKey(), s);
                return null;
            }
            if ("findDebate".equals(method.getName())) {
                var key = (DecisionPanelKey) args[1];
                return Optional.ofNullable(debates.get(key));
            }
            return null;
        });
        protocolStore = proxy(DebateProtocolStore.class, (proxy, method, args) -> {
            if ("candidates".equals(method.getName()) || "ballots".equals(method.getName())) {
                return List.of();
            }
            if ("phase".equals(method.getName()) || "currentPhase".equals(method.getName())) {
                return Optional.empty();
            }
            return null;
        });
        var gateway = proxy(io.omnnu.finbot.application.ai.port.out.AiCompletionGateway.class, (p, m, a) -> null);
        var resolver = proxy(io.omnnu.finbot.application.ai.port.out.AiRuntimeBindingResolver.class, (p, m, a) -> null);
        var audit = proxy(io.omnnu.finbot.application.ai.port.out.AiInvocationAuditStore.class, (p, m, a) -> null);
        var budget = proxy(io.omnnu.finbot.application.ai.port.out.AiBudgetReservationStore.class, (p, m, a) -> null);
        var events = proxy(io.omnnu.finbot.application.workflow.port.out.WorkflowEventPublisher.class, (p, m, a) -> null);
        var idGen = proxy(io.omnnu.finbot.application.shared.port.out.SortableIdGenerator.class, (p, m, a) -> "id_1");
        var invoker = new io.omnnu.finbot.application.ai.service.WorkflowAiInvoker(
                gateway, resolver, audit, budget, events, idGen, CLOCK);
        var aiExecutor = new AiExecutionPolicyExecutor(invoker, CLOCK);
        engine = new DecisionPanelEngine(
                executionStore,
                protocolStore,
                aiExecutor,
                CLOCK,
                Runnable::run);
    }

    @Test
    void recoversCompletedSessionDirectly() {
        var runId = new WorkflowRunId("run_test_recovery");
        var execution = executionContext(runId);
        var decisionNode = execution.definitionVersion().decisionNode();
        var session = new DebateSession(
                new DebateId("debate_run_test_recovery_principal-review"),
                runId,
                DecisionPanelKey.PRINCIPAL_REVIEW,
                DecisionPanelPurpose.PRINCIPAL_REVIEW,
                null,
                null,
                DebateStatus.COMPLETED,
                1,
                1,
                decisionNode.nodeId(),
                NOW,
                NOW,
                0);
        debates.put(session.panelKey(), session);

        var testDriver = new TestDriver(DecisionPanelKey.PRINCIPAL_REVIEW, DecisionPanelPurpose.PRINCIPAL_REVIEW);
        testDriver.recoveryResult = "RECOVERED_RESULT";

        var result = engine.execute(execution, decisionNode, List.of(), testDriver);
        assertEquals("RECOVERED_RESULT", result);
    }

    @Test
    void lowQuorumReturnsFailClosedResultWhenSeatsInsufficient() {
        var runId = new WorkflowRunId("run_test_low_quorum");
        var execution = executionContext(runId);
        var decisionNode = execution.definitionVersion().decisionNode();
        var singleParticipant = execution.definitionVersion().topologicalNodes().stream()
                .filter(n -> n.nodeType() == WorkflowNodeType.AGENT)
                .findFirst()
                .orElseThrow();

        var testDriver = new TestDriver(DecisionPanelKey.PRINCIPAL_REVIEW, DecisionPanelPurpose.PRINCIPAL_REVIEW);
        var result = engine.execute(execution, decisionNode, List.of(singleParticipant), testDriver);

        assertNotNull(result);
        assertEquals("LOW_QUORUM_RESULT", result);
    }

    private static WorkflowExecutionContext executionContext(WorkflowRunId runId) {
        var input = deterministicNode("node_input", WorkflowNodeType.INPUT);
        var agentA = agent("node_agent_alpha", "macro_role");
        var agentB = agent("node_agent_beta", "risk_role");
        var decision = deterministicNode("node_decision", WorkflowNodeType.SOCIAL_CHOICE);
        var output = deterministicNode("node_output", WorkflowNodeType.OUTPUT);
        var version = new WorkflowDefinitionVersion(
                new WorkflowVersionId("workflowversion_test_1"),
                new WorkflowDefinitionId("workflow_test_1"),
                1,
                WorkflowVersionStatus.PUBLISHED,
                1,
                new io.omnnu.finbot.domain.debate.DebateProtocolConfiguration(
                        io.omnnu.finbot.domain.debate.DebateProtocol.SDB_SCA_V1,
                        2,
                        2,
                        Duration.ofMinutes(5),
                        io.omnnu.finbot.domain.debate.CritiqueAssignmentPolicy.FULL_MATRIX),
                100,
                Duration.ofHours(1),
                100_000,
                BigDecimal.ZERO,
                WorkflowFailurePolicy.STOP,
                "0".repeat(64),
                NOW,
                NOW,
                "tester",
                List.of(input, agentA, agentB, decision, output),
                List.of(
                        edge("edge_input_alpha", input, agentA),
                        edge("edge_input_beta", input, agentB),
                        edge("edge_alpha_choice", agentA, decision),
                        edge("edge_beta_choice", agentB, decision),
                        edge("edge_choice_output", decision, output)));
        return new WorkflowExecutionContext(
                runId,
                WorkflowRunStatus.RUNNING,
                "Audit BTC/USDT Setup",
                "{\"market\":\"BTC_USDT\"}",
                version,
                null);
    }

    private static io.omnnu.finbot.domain.workflow.WorkflowEdgeDefinition edge(
            String id,
            WorkflowNodeDefinition source,
            WorkflowNodeDefinition target) {
        return new io.omnnu.finbot.domain.workflow.WorkflowEdgeDefinition(
                new io.omnnu.finbot.domain.workflow.WorkflowEdgeId(id),
                source.nodeId(),
                target.nodeId(),
                io.omnnu.finbot.domain.workflow.WorkflowActivationMode.ALL,
                io.omnnu.finbot.domain.workflow.WorkflowEdgeContextMode.EXCLUDE,
                (io.omnnu.finbot.domain.workflow.WorkflowCondition) null,
                false,
                null);
    }

    private static WorkflowNodeDefinition agent(String id, String roleKey) {
        return new WorkflowNodeDefinition(
                new WorkflowNodeId(id),
                WorkflowNodeType.AGENT,
                id,
                roleKey,
                null,
                new LogicalRoleKey(roleKey),
                new io.omnnu.finbot.domain.configuration.AiModelBinding(
                        new io.omnnu.finbot.domain.configuration.AiProviderProfileId("provider_test"),
                        "model-test",
                        io.omnnu.finbot.domain.configuration.ReasoningEffort.MAX),
                null,
                "System prompt",
                "User prompt",
                io.omnnu.finbot.domain.workflow.WorkflowOutputContract.DEBATE_ARGUMENT,
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

    private static WorkflowNodeDefinition deterministicNode(String id, WorkflowNodeType type) {
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
                null,
                type == WorkflowNodeType.SOCIAL_CHOICE
                        ? io.omnnu.finbot.domain.workflow.WorkflowOutputContract.CONSENSUS_RESULT
                        : null,
                WorkflowContextMode.NONE,
                0,
                0,
                64,
                30,
                new WorkflowRetryPolicy(1, Duration.ZERO),
                null,
                new WorkflowCanvasPosition(BigDecimal.ZERO, BigDecimal.ZERO),
                true);
    }

    private static final class TestDriver implements DecisionPanelProtocolDriver<String> {
        private final DecisionPanelKey panelKey;
        private final DecisionPanelPurpose purpose;
        String recoveryResult;

        TestDriver(DecisionPanelKey panelKey, DecisionPanelPurpose purpose) {
            this.panelKey = panelKey;
            this.purpose = purpose;
        }

        @Override
        public DecisionPanelKey panelKey() {
            return panelKey;
        }

        @Override
        public DecisionPanelPurpose purpose() {
            return purpose;
        }

        @Override
        public SdbScaPhaseExecutor.TaskCommand proposalCommand(
                WorkflowExecutionContext execution,
                WorkflowNodeDefinition node,
                SdbScaIdentityDisclosureGuard identityGuard) {
            return new SdbScaPhaseExecutor.TaskCommand(
                    node, null, DebateTaskVariant.PRIMARY, "proposal prompt", output -> output);
        }

        @Override
        public SdbScaPhaseExecutor.TaskCommand critiqueCommand(
                WorkflowExecutionContext execution,
                WorkflowNodeDefinition node,
                String targetCandidateId,
                DecisionPanelCandidateView targetView,
                SdbScaIdentityDisclosureGuard identityGuard) {
            return new SdbScaPhaseExecutor.TaskCommand(
                    node, targetCandidateId, DebateTaskVariant.PRIMARY, "critique prompt", output -> output);
        }

        @Override
        public SdbScaPhaseExecutor.TaskCommand revisionCommand(
                WorkflowExecutionContext execution,
                WorkflowNodeDefinition node,
                String ownCandidateId,
                DecisionPanelCandidateView ownView,
                List<String> critiques,
                SdbScaIdentityDisclosureGuard identityGuard) {
            return new SdbScaPhaseExecutor.TaskCommand(
                    node, ownCandidateId, DebateTaskVariant.PRIMARY, "revision prompt", output -> output);
        }

        @Override
        public SdbScaPhaseExecutor.TaskCommand ballotCommand(
                WorkflowExecutionContext execution,
                WorkflowNodeDefinition node,
                List<DecisionPanelCandidateView> candidateViews,
                BallotOrientation orientation,
                List<AnonymousCandidateId> candidateAliases) {
            return new SdbScaPhaseExecutor.TaskCommand(
                    node, null, DebateTaskVariant.FORWARD, "ballot prompt", output -> output);
        }

        @Override
        public AnonymousPreferenceBallot parseBallotPreference(
                String artifactContent,
                WorkflowNodeDefinition node,
                BallotOrientation orientation,
                List<AnonymousCandidateId> candidateAliases) {
            return new AnonymousPreferenceBallot(
                    node.logicalRoleKey(),
                    orientation,
                    List.of(Set.copyOf(candidateAliases)));
        }

        @Override
        public String reduce(
                WorkflowExecutionContext execution,
                DebateSession session,
                WorkflowNodeDefinition decisionNode,
                List<DebateCandidate> candidates,
                Map<String, DebateArtifact> revisionArtifactsById,
                SchulzeOutcome outcome,
                SchulzeDetailedResult detailed,
                boolean partial) {
            return "SUCCESSFUL_REDUCE";
        }

        @Override
        public String lowQuorumResult(
                WorkflowExecutionContext execution,
                DebateSession session,
                WorkflowNodeDefinition decisionNode,
                List<DebateCandidate> candidates,
                int roleCount) {
            return "LOW_QUORUM_RESULT";
        }

        @Override
        public Optional<String> recoverCompletedResult(
                DebateSession session,
                WorkflowNodeDefinition decisionNode) {
            return Optional.ofNullable(recoveryResult);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
