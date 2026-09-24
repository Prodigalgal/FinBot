package io.omnnu.finbot.application.workflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.application.ai.service.AiExecutionPolicyExecutor;
import io.omnnu.finbot.application.ai.service.WorkflowAiInvoker;
import io.omnnu.finbot.application.shared.port.out.SortableIdGenerator;
import io.omnnu.finbot.application.workflow.dto.DebateSession;
import io.omnnu.finbot.application.workflow.dto.DecisionPanelCandidateView;
import io.omnnu.finbot.application.workflow.dto.ParsedConsensusBallot;
import io.omnnu.finbot.application.workflow.dto.PrincipalReviewResult;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.application.workflow.port.out.DebateProtocolStore;
import io.omnnu.finbot.application.workflow.port.out.PrincipalReviewOutputParser;
import io.omnnu.finbot.application.workflow.port.out.SdbScaDocumentCodec;
import io.omnnu.finbot.application.workflow.port.out.WorkflowEventPublisher;
import io.omnnu.finbot.application.workflow.port.out.WorkflowExecutionStore;
import io.omnnu.finbot.domain.consensus.AnonymousCandidateId;
import io.omnnu.finbot.domain.consensus.AnonymousPreferenceBallot;
import io.omnnu.finbot.domain.consensus.BallotOrientation;
import io.omnnu.finbot.domain.consensus.ConsensusDecision;
import io.omnnu.finbot.domain.consensus.ConsensusDecisionId;
import io.omnnu.finbot.domain.consensus.ConsensusStatus;
import io.omnnu.finbot.domain.consensus.LogicalRoleKey;
import io.omnnu.finbot.domain.consensus.SchulzeOutcome;
import io.omnnu.finbot.domain.debate.CritiqueAssignmentPolicy;
import io.omnnu.finbot.domain.debate.DebateArtifact;
import io.omnnu.finbot.domain.debate.DebateArtifactId;
import io.omnnu.finbot.domain.debate.DebateArtifactStatus;
import io.omnnu.finbot.domain.debate.DebateCandidate;
import io.omnnu.finbot.domain.debate.DebateCandidateId;
import io.omnnu.finbot.domain.debate.DebatePhaseId;
import io.omnnu.finbot.domain.debate.DebateProtocol;
import io.omnnu.finbot.domain.debate.DebateProtocolConfiguration;
import io.omnnu.finbot.domain.debate.DebateTaskId;
import io.omnnu.finbot.domain.debate.DecisionPanelKey;
import io.omnnu.finbot.domain.debate.DecisionPanelPurpose;
import io.omnnu.finbot.domain.debate.PrincipalReviewAction;
import io.omnnu.finbot.domain.debate.PrincipalReviewDecision;
import io.omnnu.finbot.domain.research.ForecastSignal;
import io.omnnu.finbot.domain.workflow.AgentMessage;
import io.omnnu.finbot.domain.workflow.AgentMessageContent;
import io.omnnu.finbot.domain.workflow.AgentMessageStatus;
import io.omnnu.finbot.domain.workflow.AgentMessageType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrincipalReviewServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final Map<DecisionPanelKey, DebateSession> debates = new ConcurrentHashMap<>();
    private final Map<DebateId, List<AgentMessage>> messages = new ConcurrentHashMap<>();
    private final Map<DebateId, ConsensusDecision> decisions = new ConcurrentHashMap<>();

    private WorkflowExecutionStore executionStore;
    private DebateProtocolStore protocolStore;
    private PrincipalReviewOutputParser outputParser;
    private SdbScaDocumentCodec documentCodec;
    private DecisionPanelEngine panelEngine;
    private PrincipalReviewService reviewService;

    @BeforeEach
    void setUp() {
        debates.clear();
        messages.clear();
        decisions.clear();

        executionStore = proxy(WorkflowExecutionStore.class, (proxy, method, args) -> {
            var name = method.getName();
            if ("startDebate".equals(name)) {
                var s = (DebateSession) args[0];
                debates.put(s.panelKey(), s);
                return null;
            }
            if ("findDebate".equals(name)) {
                var key = (DecisionPanelKey) args[1];
                return Optional.ofNullable(debates.get(key));
            }
            if ("saveMessage".equals(name)) {
                var msg = (AgentMessage) args[0];
                messages.computeIfAbsent(msg.debateId(), k -> new ArrayList<>()).add(msg);
                return null;
            }
            if ("messages".equals(name)) {
                var id = (DebateId) args[0];
                return messages.getOrDefault(id, List.of());
            }
            if ("transitionDebate".equals(name)) {
                var id = (DebateId) args[0];
                var status = (DebateStatus) args[2];
                debates.values().stream()
                        .filter(s -> s.debateId().equals(id))
                        .findFirst()
                        .ifPresent(s -> debates.put(s.panelKey(), new DebateSession(
                                s.debateId(), s.runId(), s.panelKey(), s.panelPurpose(), s.inputHash(),
                                s.frozenInput(), status, s.configuredRounds(), 1, s.decisionNodeId(),
                                s.startedAt(), (Instant) args[4], s.version() + 1)));
                return null;
            }
            return null;
        });

        protocolStore = proxy(DebateProtocolStore.class, (proxy, method, args) -> {
            var name = method.getName();
            if ("saveDecision".equals(name)) {
                var d = (ConsensusDecision) args[0];
                decisions.put(d.debateId(), d);
                return null;
            }
            if ("decision".equals(name)) {
                var id = (DebateId) args[0];
                return Optional.ofNullable(decisions.get(id));
            }
            if ("candidates".equals(name) || "ballots".equals(name)) {
                return List.of();
            }
            if ("phase".equals(name) || "currentPhase".equals(name)) {
                return Optional.empty();
            }
            return null;
        });

        outputParser = new PrincipalReviewOutputParser() {
            @Override
            public ParsedPrincipalReview parseProposal(String output) {
                return new ParsedPrincipalReview(output, new PrincipalReviewDecision(
                        PrincipalReviewAction.CONFIRM, "Summary", "Audit rationale",
                        null, null, null, List.of(), List.of(), List.of()));
            }

            @Override
            public String parseCritique(String output) {
                return output;
            }

            @Override
            public ParsedPrincipalReview parseRevision(String output) {
                return new ParsedPrincipalReview(output, new PrincipalReviewDecision(
                        PrincipalReviewAction.CONFIRM, "Summary", "Audit rationale",
                        null, null, null, List.of(), List.of(), List.of()));
            }

            @Override
            public ParsedConsensusBallot parseBallot(
                    String output,
                    LogicalRoleKey logicalRoleKey,
                    BallotOrientation orientation,
                    List<AnonymousCandidateId> expectedCandidates) {
                var ballot = new AnonymousPreferenceBallot(
                        logicalRoleKey, orientation, List.of(Set.copyOf(expectedCandidates)));
                return new ParsedConsensusBallot(output, ballot);
            }
        };

        documentCodec = new SdbScaDocumentCodec() {
            @Override
            public String encodeCandidateRanking(List<AnonymousCandidateId> candidates) {
                return candidates.stream().map(AnonymousCandidateId::value).toList().toString();
            }

            @Override
            public String encodePairwiseMatrix(io.omnnu.finbot.domain.consensus.SchulzeDetailedResult result) {
                return "{}";
            }

            @Override
            public String encodeStrongestPaths(io.omnnu.finbot.domain.consensus.SchulzeDetailedResult result) {
                return "{}";
            }

            @Override
            public String encodeForecast(ForecastSignal forecast) {
                return "{}";
            }
        };

        var gateway = proxy(io.omnnu.finbot.application.ai.port.out.AiCompletionGateway.class, (p, m, a) -> null);
        var resolver = proxy(io.omnnu.finbot.application.ai.port.out.AiRuntimeBindingResolver.class, (p, m, a) -> null);
        var audit = proxy(io.omnnu.finbot.application.ai.port.out.AiInvocationAuditStore.class, (p, m, a) -> null);
        var budget = proxy(io.omnnu.finbot.application.ai.port.out.AiBudgetReservationStore.class, (p, m, a) -> null);
        var events = proxy(WorkflowEventPublisher.class, (p, m, a) -> null);
        var idGen = proxy(SortableIdGenerator.class, (p, m, a) -> "id_1");
        var invoker = new WorkflowAiInvoker(gateway, resolver, audit, budget, events, idGen, CLOCK);
        var aiExecutor = new AiExecutionPolicyExecutor(invoker, CLOCK);

        panelEngine = new DecisionPanelEngine(
                executionStore,
                protocolStore,
                aiExecutor,
                CLOCK,
                Runnable::run);

        reviewService = new PrincipalReviewService(
                executionStore,
                protocolStore,
                outputParser,
                documentCodec,
                CLOCK,
                panelEngine);
    }

    @Test
    void failClosedWhenUpstreamResearchNotSelected() {
        var runId = new WorkflowRunId("run_research_tied");
        var execution = executionContext(runId);

        var researchSession = new DebateSession(
                new DebateId("debate_run_research_tied_research"),
                runId,
                DecisionPanelKey.RESEARCH,
                DecisionPanelPurpose.RESEARCH,
                null,
                null,
                DebateStatus.COMPLETED,
                1,
                1,
                execution.definitionVersion().decisionNode().nodeId(),
                NOW,
                NOW,
                0);
        debates.put(DecisionPanelKey.RESEARCH, researchSession);

        var researchOutcome = SchulzeOutcome.unsuccessful(ConsensusStatus.TIED, List.of(), 2);
        var tiedDecision = new ConsensusDecision(
                new ConsensusDecisionId("decision_research_tied"),
                researchSession.debateId(),
                researchOutcome,
                null,
                "{}",
                "{}",
                "[]",
                null,
                "Research consensus tied",
                "0".repeat(64),
                NOW);
        decisions.put(researchSession.debateId(), tiedDecision);

        var result = reviewService.run(execution);

        assertNotNull(result);
        assertFalse(result.approved());
        assertNull(result.reviewDecision());
        assertEquals("上游研究未达成严格共识（状态为 TIED），主审面板触发安全关闭（fail-closed），不进入投票，输出 NO_ACTION。",
                result.consensusDecision().explanation());
        assertTrue(result.message().content().summary().contains("上游研究未达成共识"));
    }

    @Test
    void failClosedWhenUpstreamResearchDecisionMissing() {
        var runId = new WorkflowRunId("run_research_missing_dec");
        var execution = executionContext(runId);

        var researchSession = new DebateSession(
                new DebateId("debate_run_research_missing_research"),
                runId,
                DecisionPanelKey.RESEARCH,
                DecisionPanelPurpose.RESEARCH,
                null,
                null,
                DebateStatus.COMPLETED,
                1,
                1,
                execution.definitionVersion().decisionNode().nodeId(),
                NOW,
                NOW,
                0);
        debates.put(DecisionPanelKey.RESEARCH, researchSession);

        var result = reviewService.run(execution);

        assertNotNull(result);
        assertFalse(result.approved());
        assertNull(result.reviewDecision());
        assertTrue(result.consensusDecision().explanation().contains("MISSING"));
    }

    @Test
    void lowQuorumReturnsFailClosedResult() {
        var runId = new WorkflowRunId("run_low_quorum_review");
        var execution = new WorkflowExecutionContext(
                runId,
                WorkflowRunStatus.RUNNING,
                "Audit BTC/USDT",
                "{}",
                versionWithConditionallyDisabledParticipant(),
                null);

        var result = reviewService.run(execution);

        assertNotNull(result);
        assertFalse(result.approved());
        assertNull(result.reviewDecision());
        assertEquals(ConsensusStatus.LOW_QUORUM, result.consensusDecision().outcome().status());
    }

    @Test
    void recoverCompletedResultRecoversDirectly() {
        var runId = new WorkflowRunId("run_recovered_review");
        var execution = executionContext(runId);
        var decisionNode = execution.definitionVersion().decisionNode();

        var session = new DebateSession(
                new DebateId("debate_run_recovered_review_principal-review"),
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
        debates.put(DecisionPanelKey.PRINCIPAL_REVIEW, session);

        var outcome = SchulzeOutcome.selected(new AnonymousCandidateId("cand_winner"), List.of(), 2);
        var winningDecisionJson = """
                {"action":"CONFIRM","summary":"Confirmed","auditRationale":"Solid thesis"}
                """;
        var decision = new ConsensusDecision(
                new ConsensusDecisionId("decision_recovered"),
                session.debateId(),
                outcome,
                new DebateCandidateId("candidate_winner_id"),
                "{}",
                "{}",
                "[]",
                winningDecisionJson,
                "Approved",
                "0".repeat(64),
                NOW);
        decisions.put(session.debateId(), decision);

        var message = new AgentMessage(
                new io.omnnu.finbot.domain.workflow.AgentMessageId("message_recovered"),
                session.debateId(),
                runId,
                decisionNode.nodeId(),
                "主审独立审计",
                0,
                3,
                AgentMessageType.CONSENSUS_RESULT,
                AgentMessageStatus.COMPLETED,
                new AgentMessageContent("主审独立审计通过 (CONFIRM)", "Solid thesis", null, List.of(), List.of(), List.of(), List.of()),
                List.of(),
                NOW);
        messages.computeIfAbsent(session.debateId(), k -> new ArrayList<>()).add(message);

        var result = reviewService.run(execution);

        assertNotNull(result);
        assertEquals(session.debateId(), result.session().debateId());
        assertEquals(message.messageId(), result.message().messageId());
    }

    @Test
    void reduceWithConfirmApprovesExecution() {
        var runId = new WorkflowRunId("run_reduce_confirm");
        var execution = executionContext(runId);
        var decisionNode = execution.definitionVersion().decisionNode();
        var driver = reviewService.createDriver("Research summary");

        var session = new DebateSession(
                new DebateId("debate_run_reduce_confirm_principal-review"),
                runId,
                DecisionPanelKey.PRINCIPAL_REVIEW,
                DecisionPanelPurpose.PRINCIPAL_REVIEW,
                null,
                null,
                DebateStatus.RUNNING,
                1,
                0,
                decisionNode.nodeId(),
                NOW,
                null,
                0);

        var candId = new DebateCandidateId("candidate_c1");
        var alias = new AnonymousCandidateId("candidate_alias1");
        var revId = new DebateArtifactId("debate_artifact_rev1");
        var candidate = new DebateCandidate(
                candId,
                session.debateId(),
                new WorkflowNodeId("node_agent_1"),
                new LogicalRoleKey("role_1"),
                alias,
                new DebateArtifactId("debate_artifact_prop1"),
                revId,
                NOW);

        var revArtifact = new DebateArtifact(
                revId,
                new DebateTaskId("debate_task_rev1"),
                new DebatePhaseId("phase_rev1"),
                DebateArtifactStatus.REVEALED,
                "0".repeat(64),
                "{\"action\":\"CONFIRM\",\"summary\":\"Confirmed\",\"argument\":\"Thesis verified\"}",
                NOW,
                NOW);

        var outcome = SchulzeOutcome.selected(alias, List.of(), 2);
        var result = driver.reduce(
                execution,
                session,
                decisionNode,
                List.of(candidate),
                Map.of(revId.value(), revArtifact),
                outcome,
                null,
                false);

        assertNotNull(result);
        assertTrue(result.approved());
        assertNotNull(result.reviewDecision());
        assertEquals(PrincipalReviewAction.CONFIRM, result.reviewDecision().action());
        assertEquals("Summary", result.reviewDecision().summary());
        assertEquals("Audit rationale", result.reviewDecision().auditRationale());
        assertTrue(result.message().content().summary().contains("CONFIRM"));
    }

    @Test
    void reduceWithTightenApprovesAndPreservesConstraints() {
        var runId = new WorkflowRunId("run_reduce_tighten");
        var execution = executionContext(runId);
        var decisionNode = execution.definitionVersion().decisionNode();

        var session = new DebateSession(
                new DebateId("debate_run_reduce_tighten_principal-review"),
                runId,
                DecisionPanelKey.PRINCIPAL_REVIEW,
                DecisionPanelPurpose.PRINCIPAL_REVIEW,
                null,
                null,
                DebateStatus.RUNNING,
                1,
                0,
                decisionNode.nodeId(),
                NOW,
                null,
                0);

        var candId = new DebateCandidateId("candidate_c2");
        var alias = new AnonymousCandidateId("candidate_alias2");
        var revId = new DebateArtifactId("debate_artifact_rev2");
        var candidate = new DebateCandidate(
                candId,
                session.debateId(),
                new WorkflowNodeId("node_agent_1"),
                new LogicalRoleKey("role_1"),
                alias,
                new DebateArtifactId("debate_artifact_prop2"),
                revId,
                NOW);

        var tightenParser = new PrincipalReviewOutputParser() {
            @Override
            public ParsedPrincipalReview parseProposal(String output) { return null; }
            @Override
            public String parseCritique(String output) { return output; }
            @Override
            public ParsedPrincipalReview parseRevision(String output) {
                return new ParsedPrincipalReview(output, new PrincipalReviewDecision(
                        PrincipalReviewAction.TIGHTEN,
                        "Tightened bounds",
                        "High volatility detected",
                        new BigDecimal("0.70"),
                        new BigDecimal("3.0"),
                        new BigDecimal("0.02"),
                        List.of("Claim 1"),
                        List.of(),
                        List.of("Vol risk")));
            }
            @Override
            public ParsedConsensusBallot parseBallot(String output, LogicalRoleKey key, BallotOrientation o, List<AnonymousCandidateId> c) { return null; }
        };

        var customService = new PrincipalReviewService(
                executionStore, protocolStore, tightenParser, documentCodec, CLOCK, panelEngine);
        var customDriver = customService.createDriver("Research summary");

        var revArtifact = new DebateArtifact(
                revId,
                new DebateTaskId("debate_task_rev2"),
                new DebatePhaseId("phase_rev2"),
                DebateArtifactStatus.REVEALED,
                "0".repeat(64),
                "{}",
                NOW,
                NOW);

        var outcome = SchulzeOutcome.selected(alias, List.of(), 2);
        var result = customDriver.reduce(
                execution,
                session,
                decisionNode,
                List.of(candidate),
                Map.of(revId.value(), revArtifact),
                outcome,
                null,
                false);

        assertNotNull(result);
        assertTrue(result.approved());
        assertNotNull(result.reviewDecision());
        assertEquals(PrincipalReviewAction.TIGHTEN, result.reviewDecision().action());
        assertEquals(new BigDecimal("0.70"), result.reviewDecision().tightenedConfidence());
        assertEquals(new BigDecimal("3.0"), result.reviewDecision().tightenedMaxLeverage());
        assertEquals(new BigDecimal("0.02"), result.reviewDecision().tightenedStopDistance());
        assertTrue(result.message().content().summary().contains("TIGHTEN"));
    }

    @Test
    void reduceWithRejectRejectsExecution() {
        var runId = new WorkflowRunId("run_reduce_reject");
        var execution = executionContext(runId);
        var decisionNode = execution.definitionVersion().decisionNode();

        var session = new DebateSession(
                new DebateId("debate_run_reduce_reject_principal-review"),
                runId,
                DecisionPanelKey.PRINCIPAL_REVIEW,
                DecisionPanelPurpose.PRINCIPAL_REVIEW,
                null,
                null,
                DebateStatus.RUNNING,
                1,
                0,
                decisionNode.nodeId(),
                NOW,
                null,
                0);

        var candId = new DebateCandidateId("candidate_c3");
        var alias = new AnonymousCandidateId("candidate_alias3");
        var revId = new DebateArtifactId("debate_artifact_rev3");
        var candidate = new DebateCandidate(
                candId,
                session.debateId(),
                new WorkflowNodeId("node_agent_1"),
                new LogicalRoleKey("role_1"),
                alias,
                new DebateArtifactId("debate_artifact_prop3"),
                revId,
                NOW);

        var rejectParser = new PrincipalReviewOutputParser() {
            @Override
            public ParsedPrincipalReview parseProposal(String output) { return null; }
            @Override
            public String parseCritique(String output) { return output; }
            @Override
            public ParsedPrincipalReview parseRevision(String output) {
                return new ParsedPrincipalReview(output, new PrincipalReviewDecision(
                        PrincipalReviewAction.REJECT,
                        "Rejecting thesis",
                        "Counterexample found",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of("Severe liquidity drain"),
                        List.of("Insolvency risk")));
            }
            @Override
            public ParsedConsensusBallot parseBallot(String output, LogicalRoleKey key, BallotOrientation o, List<AnonymousCandidateId> c) { return null; }
        };

        var customService = new PrincipalReviewService(
                executionStore, protocolStore, rejectParser, documentCodec, CLOCK, panelEngine);
        var customDriver = customService.createDriver("Research summary");

        var revArtifact = new DebateArtifact(
                revId,
                new DebateTaskId("debate_task_rev3"),
                new DebatePhaseId("phase_rev3"),
                DebateArtifactStatus.REVEALED,
                "0".repeat(64),
                "{}",
                NOW,
                NOW);

        var outcome = SchulzeOutcome.selected(alias, List.of(), 2);
        var result = customDriver.reduce(
                execution,
                session,
                decisionNode,
                List.of(candidate),
                Map.of(revId.value(), revArtifact),
                outcome,
                null,
                false);

        assertNotNull(result);
        assertFalse(result.approved());
        assertNotNull(result.reviewDecision());
        assertEquals(PrincipalReviewAction.REJECT, result.reviewDecision().action());
        assertTrue(result.message().content().summary().contains("REJECT"));
    }

    @Test
    void reduceWithTiedFailsClosed() {
        var runId = new WorkflowRunId("run_reduce_tied");
        var execution = executionContext(runId);
        var decisionNode = execution.definitionVersion().decisionNode();
        var driver = reviewService.createDriver("Research summary");

        var session = new DebateSession(
                new DebateId("debate_run_reduce_tied_principal-review"),
                runId,
                DecisionPanelKey.PRINCIPAL_REVIEW,
                DecisionPanelPurpose.PRINCIPAL_REVIEW,
                null,
                null,
                DebateStatus.RUNNING,
                1,
                0,
                decisionNode.nodeId(),
                NOW,
                null,
                0);

        var outcome = SchulzeOutcome.unsuccessful(ConsensusStatus.TIED, List.of(), 2);
        var result = driver.reduce(
                execution,
                session,
                decisionNode,
                List.of(),
                Map.of(),
                outcome,
                null,
                false);

        assertNotNull(result);
        assertFalse(result.approved());
        assertNull(result.reviewDecision());
        assertEquals(ConsensusStatus.TIED, result.consensusDecision().outcome().status());
        assertTrue(result.consensusDecision().explanation().contains("并列"));
    }

    private static WorkflowExecutionContext executionContext(WorkflowRunId runId) {
        return new WorkflowExecutionContext(
                runId,
                WorkflowRunStatus.RUNNING,
                "Audit BTC/USDT Setup",
                "{\"market\":\"BTC_USDT\"}",
                versionWithParticipantCount(2),
                null);
    }

    private static WorkflowDefinitionVersion versionWithParticipantCount(int agentCount) {
        var input = deterministicNode("node_input", WorkflowNodeType.INPUT);
        var decision = deterministicNode("node_decision", WorkflowNodeType.SOCIAL_CHOICE);
        var output = deterministicNode("node_output", WorkflowNodeType.OUTPUT);

        var nodes = new ArrayList<WorkflowNodeDefinition>();
        nodes.add(input);
        var edges = new ArrayList<io.omnnu.finbot.domain.workflow.WorkflowEdgeDefinition>();

        for (int i = 1; i <= agentCount; i++) {
            var agentNode = agent("node_agent_" + i, "role_" + i);
            nodes.add(agentNode);
            edges.add(edge("edge_in_" + i, input, agentNode));
            edges.add(edge("edge_out_" + i, agentNode, decision));
        }
        nodes.add(decision);
        nodes.add(output);
        edges.add(edge("edge_dec_out", decision, output));

        return new WorkflowDefinitionVersion(
                new WorkflowVersionId("workflowversion_test_review"),
                new WorkflowDefinitionId("workflow_test_review"),
                1,
                WorkflowVersionStatus.PUBLISHED,
                1,
                new DebateProtocolConfiguration(
                        DebateProtocol.SDB_SCA_V1,
                        2,
                        2,
                        Duration.ofMinutes(5),
                        CritiqueAssignmentPolicy.FULL_MATRIX),
                100,
                Duration.ofHours(1),
                100_000,
                BigDecimal.ZERO,
                WorkflowFailurePolicy.STOP,
                "0".repeat(64),
                NOW,
                NOW,
                "tester",
                nodes,
                edges);
    }

    private static WorkflowDefinitionVersion versionWithConditionallyDisabledParticipant() {
        var input = deterministicNode("node_input", WorkflowNodeType.INPUT);
        var agent1 = agent("node_agent_1", "role_1");
        var agent2 = agent("node_agent_2", "role_2");
        var decision = deterministicNode("node_decision", WorkflowNodeType.SOCIAL_CHOICE);
        var output = deterministicNode("node_output", WorkflowNodeType.OUTPUT);

        var condition = new io.omnnu.finbot.domain.workflow.WorkflowCondition(
                "context.non_existent_key",
                io.omnnu.finbot.domain.workflow.WorkflowConditionOperator.EXISTS,
                null);

        var edges = List.of(
                edge("edge_in_1", input, agent1),
                new io.omnnu.finbot.domain.workflow.WorkflowEdgeDefinition(
                        new io.omnnu.finbot.domain.workflow.WorkflowEdgeId("edge_in_2"),
                        input.nodeId(),
                        agent2.nodeId(),
                        io.omnnu.finbot.domain.workflow.WorkflowActivationMode.ALL,
                        io.omnnu.finbot.domain.workflow.WorkflowEdgeContextMode.EXCLUDE,
                        condition,
                        false,
                        null),
                edge("edge_out_1", agent1, decision),
                edge("edge_out_2", agent2, decision),
                edge("edge_dec_out", decision, output));

        return new WorkflowDefinitionVersion(
                new WorkflowVersionId("workflowversion_test_review_low_quorum"),
                new WorkflowDefinitionId("workflow_test_review_low_quorum"),
                1,
                WorkflowVersionStatus.PUBLISHED,
                1,
                new DebateProtocolConfiguration(
                        DebateProtocol.SDB_SCA_V1,
                        2,
                        2,
                        Duration.ofMinutes(5),
                        CritiqueAssignmentPolicy.FULL_MATRIX),
                100,
                Duration.ofHours(1),
                100_000,
                BigDecimal.ZERO,
                WorkflowFailurePolicy.STOP,
                "0".repeat(64),
                NOW,
                NOW,
                "tester",
                List.of(input, agent1, agent2, decision, output),
                edges);
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
                null,
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

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
