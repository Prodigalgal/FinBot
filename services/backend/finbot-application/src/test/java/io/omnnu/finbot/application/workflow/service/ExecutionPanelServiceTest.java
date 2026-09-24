package io.omnnu.finbot.application.workflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.application.ai.dto.AiInvocationResult;
import io.omnnu.finbot.application.ai.port.out.AiBudgetReservationStore;
import io.omnnu.finbot.application.ai.port.out.AiCompletionGateway;
import io.omnnu.finbot.application.ai.port.out.AiInvocationAuditStore;
import io.omnnu.finbot.application.ai.port.out.AiRuntimeBindingResolver;
import io.omnnu.finbot.application.ai.service.AiExecutionPolicyExecutor;
import io.omnnu.finbot.application.ai.service.WorkflowAiInvoker;
import io.omnnu.finbot.application.market.dto.ResearchMarketScope;
import io.omnnu.finbot.application.shared.port.out.SortableIdGenerator;
import io.omnnu.finbot.application.trading.dto.TradeDecisionDraft;
import io.omnnu.finbot.application.workflow.dto.DebateSession;
import io.omnnu.finbot.application.workflow.dto.ParsedConsensusBallot;
import io.omnnu.finbot.application.workflow.dto.PrincipalReviewResult;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.application.workflow.port.out.DebateProtocolStore;
import io.omnnu.finbot.application.workflow.port.out.ExecutionPanelOutputParser;
import io.omnnu.finbot.application.workflow.port.out.SdbScaDocumentCodec;
import io.omnnu.finbot.application.workflow.port.out.WorkflowEventPublisher;
import io.omnnu.finbot.application.workflow.port.out.WorkflowExecutionStore;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.consensus.AnonymousCandidateId;
import io.omnnu.finbot.domain.consensus.AnonymousPreferenceBallot;
import io.omnnu.finbot.domain.consensus.BallotOrientation;
import io.omnnu.finbot.domain.consensus.ConsensusDecision;
import io.omnnu.finbot.domain.consensus.ConsensusDecisionId;
import io.omnnu.finbot.domain.consensus.ConsensusStatus;
import io.omnnu.finbot.domain.consensus.LogicalRoleKey;
import io.omnnu.finbot.domain.consensus.SchulzeDetailedResult;
import io.omnnu.finbot.domain.consensus.SchulzeOutcome;
import io.omnnu.finbot.domain.debate.CritiqueAssignmentPolicy;
import io.omnnu.finbot.domain.debate.DebateArtifact;
import io.omnnu.finbot.domain.debate.DebateCandidate;
import io.omnnu.finbot.domain.debate.DebateCandidateId;
import io.omnnu.finbot.domain.debate.DebateProtocol;
import io.omnnu.finbot.domain.debate.DebateProtocolConfiguration;
import io.omnnu.finbot.domain.debate.DecisionPanelKey;
import io.omnnu.finbot.domain.debate.DecisionPanelPurpose;
import io.omnnu.finbot.domain.debate.PrincipalReviewAction;
import io.omnnu.finbot.domain.debate.PrincipalReviewDecision;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.market.Price;
import io.omnnu.finbot.domain.trading.Confidence;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import io.omnnu.finbot.domain.workflow.AgentMessage;
import io.omnnu.finbot.domain.workflow.AgentMessageContent;
import io.omnnu.finbot.domain.workflow.AgentMessageId;
import io.omnnu.finbot.domain.workflow.AgentMessageStatus;
import io.omnnu.finbot.domain.workflow.AgentMessageType;
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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExecutionPanelServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-20T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final WorkflowRunId RUN_ID = new WorkflowRunId("run_execution_panel_test");

    @Test
    void failsClosedWhenPrincipalReviewRejected() {
        var context = testWorkflowContext();
        var debateId = new DebateId("debate_exec_rejected");
        var session = new DebateSession(
                debateId,
                RUN_ID,
                DecisionPanelKey.PRINCIPAL_REVIEW,
                DecisionPanelPurpose.PRINCIPAL_REVIEW,
                null,
                null,
                DebateStatus.COMPLETED,
                1,
                1,
                new WorkflowNodeId("node_decision"),
                NOW,
                NOW,
                0);
        var dummyMessage = new AgentMessage(
                new AgentMessageId("message_dummy"),
                debateId,
                RUN_ID,
                new WorkflowNodeId("node_decision"),
                "主审独立审计",
                0,
                1,
                AgentMessageType.CONSENSUS_RESULT,
                AgentMessageStatus.COMPLETED,
                new AgentMessageContent("主审驳回", "存在不可调和风险", BigDecimal.ZERO, List.of(), List.of(), List.of(), List.of(), null),
                List.of(),
                NOW);
        var dummyConsensus = new ConsensusDecision(
                new ConsensusDecisionId("decision_" + "0".repeat(40)),
                debateId,
                SchulzeOutcome.unsuccessful(ConsensusStatus.NO_STRICT_WINNER, List.of(), 0),
                null,
                "{}",
                "{}",
                "[]",
                null,
                "rejected",
                "0".repeat(64),
                NOW);
        var rejectedReview = new PrincipalReviewResult(
                session,
                dummyMessage,
                dummyConsensus,
                new PrincipalReviewDecision(
                        PrincipalReviewAction.REJECT,
                        "驳回",
                        "反例存在",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of("反例1"),
                        List.of("理由1")),
                false,
                false);

        var storedDebates = new HashMap<DebateId, DebateSession>();
        var storedMessages = new ArrayList<AgentMessage>();
        var storedDecisions = new HashMap<DebateId, ConsensusDecision>();

        var workflowStore = proxy(WorkflowExecutionStore.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "findDebate" -> Optional.empty();
            case "startDebate" -> {
                var s = (DebateSession) arguments[0];
                storedDebates.put(s.debateId(), s);
                yield null;
            }
            case "saveDebate" -> {
                var s = (DebateSession) arguments[0];
                storedDebates.put(s.debateId(), s);
                yield null;
            }
            case "saveMessage" -> {
                storedMessages.add((AgentMessage) arguments[0]);
                yield null;
            }
            case "messages" -> storedMessages;
            case "completeDebate" -> null;
            case "transitionDebate" -> null;
            default -> throw new AssertionError("Unexpected call: " + method.getName());
        });

        var protocolStore = proxy(DebateProtocolStore.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "decision" -> Optional.empty();
            case "saveDecision" -> {
                var d = (ConsensusDecision) arguments[0];
                storedDecisions.put(d.debateId(), d);
                yield null;
            }
            default -> throw new AssertionError("Unexpected call: " + method.getName());
        });

        var service = new ExecutionPanelService(
                workflowStore,
                protocolStore,
                dummyOutputParser(),
                dummyCodec(),
                CLOCK,
                Runnable::run,
                dummyEngine());

        var result = service.run(context, rejectedReview);

        assertFalse(result.approved());
        assertNotNull(result.message());
        assertTrue(result.message().content().argument().contains("主审独立审计未通过"));
    }

    @Test
    void appliesPrincipalReviewTighteningOnConfidenceAndStopLoss() {
        var context = testWorkflowContext();
        var debateId = new DebateId("debate_exec_tighten");
        var session = new DebateSession(
                debateId,
                RUN_ID,
                DecisionPanelKey.PRINCIPAL_REVIEW,
                DecisionPanelPurpose.PRINCIPAL_REVIEW,
                null,
                null,
                DebateStatus.COMPLETED,
                1,
                1,
                new WorkflowNodeId("node_decision"),
                NOW,
                NOW,
                0);
        var dummyMessage = new AgentMessage(
                new AgentMessageId("message_dummy"),
                debateId,
                RUN_ID,
                new WorkflowNodeId("node_decision"),
                "主审独立审计",
                0,
                1,
                AgentMessageType.CONSENSUS_RESULT,
                AgentMessageStatus.COMPLETED,
                new AgentMessageContent("主审收紧", "收紧置信度与止损", new BigDecimal("0.70"), List.of(), List.of(), List.of(), List.of(), null),
                List.of(),
                NOW);
        var dummyConsensus = new ConsensusDecision(
                new ConsensusDecisionId("decision_" + "0".repeat(40)),
                debateId,
                SchulzeOutcome.selected(new AnonymousCandidateId("candidate_1"), List.of(new AnonymousCandidateId("candidate_1")), 1),
                new DebateCandidateId("candidate_1"),
                "{}",
                "{}",
                "[]",
                null,
                "tightened",
                "0".repeat(64),
                NOW);
        var tightenedReview = new PrincipalReviewResult(
                session,
                dummyMessage,
                dummyConsensus,
                new PrincipalReviewDecision(
                        PrincipalReviewAction.TIGHTEN,
                        "收紧",
                        "收紧置信度到0.70，止损收紧至2%",
                        new BigDecimal("0.70"),
                        new BigDecimal("5"),
                        new BigDecimal("0.02"), // Max 2% stop loss distance
                        List.of(),
                        List.of(),
                        List.of("风险警告")),
                true,
                false);

        var service = new ExecutionPanelService(
                unused(WorkflowExecutionStore.class),
                unused(DebateProtocolStore.class),
                dummyOutputParser(),
                dummyCodec(),
                CLOCK,
                Runnable::run,
                dummyEngine());

        var driver = service.new ExecutionDecisionPanelDriver(tightenedReview);

        // Raw draft proposes BUY BTC at 60,000 with confidence 0.85 and stop at 57,000 (5% distance, exceeding 2%)
        var rawDraft = new TradeDecisionDraft(
                DirectionalAction.BUY,
                new InstrumentSymbol("BTCUSDT"),
                new Confidence(new BigDecimal("0.85")),
                new Price(new BigDecimal("60000.0")),
                new Price(new BigDecimal("65000.0")),
                new Price(new BigDecimal("57000.0")), // 5% stop distance
                List.of("突破买入"),
                List.of("ref1"));

        var constrained = driver.applyPrincipalReviewTightening(rawDraft, tightenedReview);

        // Confidence must be clamped to 0.70
        assertEquals(0, new BigDecimal("0.70").compareTo(constrained.confidence().value()));
        // Stop loss distance must be tightened to 2%: 60000 * (1 - 0.02) = 58800.0
        assertEquals(0, new BigDecimal("58800.0").compareTo(constrained.invalidationPrice().value()));
        assertTrue(constrained.rationale().stream().anyMatch(r -> r.contains("强制收紧置信度至: 0.70")));
        assertTrue(constrained.rationale().stream().anyMatch(r -> r.contains("强制收紧止损距离至: 0.02")));
    }

    private static WorkflowExecutionContext testWorkflowContext() {
        var input = deterministicNode("node_input", WorkflowNodeType.INPUT);
        var exec1 = node("node_exec_1", "role_exec_1");
        var exec2 = node("node_exec_2", "role_exec_2");
        var exec3 = node("node_exec_3", "role_exec_3");
        var decision = deterministicNode("node_decision", WorkflowNodeType.SOCIAL_CHOICE);
        var output = deterministicNode("node_output", WorkflowNodeType.OUTPUT);

        var version = new WorkflowDefinitionVersion(
                new WorkflowVersionId("workflowversion_test"),
                new WorkflowDefinitionId("workflow_test"),
                1,
                WorkflowVersionStatus.PUBLISHED,
                1,
                new DebateProtocolConfiguration(
                        DebateProtocol.SDB_SCA_V1,
                        3,
                        3,
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
                List.of(input, exec1, exec2, exec3, decision, output),
                List.of(
                        edge("edge_in_1", input, exec1),
                        edge("edge_in_2", input, exec2),
                        edge("edge_in_3", input, exec3),
                        edge("edge_out_1", exec1, decision),
                        edge("edge_out_2", exec2, decision),
                        edge("edge_out_3", exec3, decision),
                        edge("edge_dec_out", decision, output)));

        return new WorkflowExecutionContext(
                RUN_ID,
                WorkflowRunStatus.COMPLETED,
                "Execute BTCUSDT",
                "{}",
                version,
                new ResearchMarketScope(
                        new InstrumentId("instrument_btc"),
                        ExchangeVenue.GATE,
                        ExchangeEnvironment.TESTNET,
                        "BTCUSDT",
                        300,
                        3600,
                        new BigDecimal("60000")));
    }

    private static WorkflowNodeDefinition node(String id, String role) {
        return new WorkflowNodeDefinition(
                new WorkflowNodeId(id),
                WorkflowNodeType.AGENT,
                id,
                role,
                null,
                new LogicalRoleKey(role),
                new AiModelBinding(new AiProviderProfileId("provider_test"), "model_test", ReasoningEffort.MAX),
                null,
                "System prompt",
                "User prompt",
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
                        ? WorkflowOutputContract.CONSENSUS_RESULT
                        : null,
                WorkflowContextMode.NONE,
                0,
                0,
                64,
                30,
                new WorkflowRetryPolicy(1, Duration.ZERO),
                type == WorkflowNodeType.SOCIAL_CHOICE ? "EXECUTION" : null,
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
                WorkflowEdgeContextMode.EXCLUDE,
                null,
                false,
                null);
    }

    private static ExecutionPanelOutputParser dummyOutputParser() {
        return proxy(ExecutionPanelOutputParser.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "encodeCandidateRanking" -> "[]";
            case "encodePairwiseMatrix" -> "{}";
            case "encodeStrongestPaths" -> "{}";
            case "encodeDecisionDraft" -> "{}";
            default -> throw new AssertionError("Unexpected parser call: " + method.getName());
        });
    }

    private static SdbScaDocumentCodec dummyCodec() {
        return proxy(SdbScaDocumentCodec.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "encodeCandidateRanking" -> "[]";
            case "encodeSchulzeOutcome" -> "{}";
            default -> throw new AssertionError("Unexpected codec call: " + method.getName());
        });
    }

    private static DecisionPanelEngine dummyEngine() {
        return new DecisionPanelEngine(
                unused(WorkflowExecutionStore.class),
                unused(DebateProtocolStore.class),
                new AiExecutionPolicyExecutor(
                        new WorkflowAiInvoker(
                                unused(AiCompletionGateway.class),
                                unused(AiRuntimeBindingResolver.class),
                                unused(AiInvocationAuditStore.class),
                                unused(AiBudgetReservationStore.class),
                                unused(WorkflowEventPublisher.class),
                                unused(SortableIdGenerator.class),
                                CLOCK),
                        CLOCK),
                CLOCK,
                Runnable::run);
    }

    private static <T> T unused(Class<T> type) {
        return proxy(type, (ignored, method, arguments) -> {
            throw new AssertionError("Unexpected call on unused: " + method.getName());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }
}
