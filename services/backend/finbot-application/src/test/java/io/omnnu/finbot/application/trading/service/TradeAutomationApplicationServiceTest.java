package io.omnnu.finbot.application.trading.service;

import io.omnnu.finbot.application.trading.dto.StoredEstimatedTradeProjection;
import io.omnnu.finbot.application.trading.dto.TradeAutomationResult;
import io.omnnu.finbot.application.trading.dto.TradeAutomationStatus;
import io.omnnu.finbot.application.trading.dto.TradeExecutionAiStage;
import io.omnnu.finbot.application.trading.dto.TradeExecutionAiStageConfig;
import io.omnnu.finbot.application.trading.port.out.TradeAutomationStore;
import io.omnnu.finbot.application.trading.port.out.TradeDecisionOutputParser;
import io.omnnu.finbot.application.trading.service.TradeAutomationApplicationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.omnnu.finbot.application.ai.port.out.AiBudgetReservationStore;
import io.omnnu.finbot.application.ai.service.AiExecutionPolicyExecutor;
import io.omnnu.finbot.application.ai.port.out.AiCompletionGateway;
import io.omnnu.finbot.application.ai.port.out.AiInvocationAuditStore;
import io.omnnu.finbot.application.ai.port.out.AiRuntimeBindingResolver;
import io.omnnu.finbot.application.ai.service.WorkflowAiInvoker;
import io.omnnu.finbot.application.exchange.port.in.PaperOrderExecutionUseCase;
import io.omnnu.finbot.application.market.dto.ResearchMarketScope;
import io.omnnu.finbot.application.shared.port.out.SortableIdGenerator;
import io.omnnu.finbot.application.workflow.dto.DebateSession;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.application.workflow.port.out.WorkflowEventPublisher;
import io.omnnu.finbot.application.workflow.port.out.WorkflowExecutionStore;
import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.consensus.LogicalRoleKey;
import io.omnnu.finbot.domain.debate.CritiqueAssignmentPolicy;
import io.omnnu.finbot.domain.debate.DebateProtocol;
import io.omnnu.finbot.domain.debate.DebateProtocolConfiguration;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.market.Price;
import io.omnnu.finbot.domain.research.ForecastDirection;
import io.omnnu.finbot.domain.research.ForecastSignal;
import io.omnnu.finbot.domain.risk.ProjectionInstrumentSpec;
import io.omnnu.finbot.domain.risk.RiskPolicy;
import io.omnnu.finbot.domain.risk.MarginRiskEngine;
import io.omnnu.finbot.domain.risk.EstimatedTradeEngine;
import io.omnnu.finbot.domain.trading.Confidence;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import io.omnnu.finbot.domain.trading.DirectionalTradeDecision;
import io.omnnu.finbot.domain.trading.NonDirectionalAction;
import io.omnnu.finbot.domain.trading.NonDirectionalTradeDecision;
import io.omnnu.finbot.domain.trading.TradeDecision;
import io.omnnu.finbot.domain.trading.TradeDecisionId;
import io.omnnu.finbot.domain.trading.TradeProposal;
import io.omnnu.finbot.domain.trading.TradeProposalId;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TradeAutomationApplicationServiceTest {
    private static final WorkflowRunId RUN_ID = new WorkflowRunId("run_trade_retry_test");
    private static final Instant NOW = Instant.parse("2026-07-14T12:45:00Z");

    @Test
    void createsContractValidNodesForBothExecutionStages() {
        var draft = TradeAutomationApplicationService.executionNode(stage(TradeExecutionAiStage.DRAFT));
        var reflection = TradeAutomationApplicationService.executionNode(stage(TradeExecutionAiStage.REFLECTION));

        assertEquals("node_execution_draft", draft.nodeId().value());
        assertEquals(WorkflowNodeType.EXECUTION_REVIEW, draft.nodeType());
        assertEquals(WorkflowOutputContract.TRADE_DECISIONS, draft.outputContract());
        assertEquals("node_execution_reflection", reflection.nodeId().value());
        assertEquals(WorkflowNodeType.EXECUTION_REVIEW, reflection.nodeType());
        assertEquals(WorkflowOutputContract.EXECUTION_VERDICT, reflection.outputContract());
    }

    @Test
    void failedAutomationAttemptsAProtectedRestart() {
        var startCalls = new AtomicInteger();
        var service = service(retryStore(startCalls, true));

        var failure = assertThrows(
                CompletionException.class,
                () -> service.execute(RUN_ID).toCompletableFuture().join());

        assertEquals("Workflow run does not exist", rootCause(failure).getMessage());
        assertEquals(1, startCalls.get());
    }

    @Test
    void unsafeFailedAutomationDoesNotReportSuccess() {
        var startCalls = new AtomicInteger();
        var service = service(retryStore(startCalls, false));

        var failure = assertThrows(
                CompletionException.class,
                () -> service.execute(RUN_ID).toCompletableFuture().join());

        assertEquals(
                "Trade automation is already running or cannot be retried after durable trading state was created",
                rootCause(failure).getMessage());
        assertEquals(1, startCalls.get());
    }

    @Test
    void estimatesResearchOnlyInstrumentWithoutCreatingAnOmsOrder() {
        var storedProjection = new AtomicReference<StoredEstimatedTradeProjection>();
        var completedStatus = new AtomicReference<TradeAutomationStatus>();
        var store = proxy(TradeAutomationStore.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "projectionCandidates" -> List.of(projectionInstrument());
            case "saveEstimatedTradeProjection" -> {
                storedProjection.set((StoredEstimatedTradeProjection) arguments[0]);
                yield null;
            }
            case "complete" -> {
                completedStatus.set((TradeAutomationStatus) arguments[1]);
                assertEquals(List.of(), arguments[5]);
                yield null;
            }
            default -> throw new AssertionError("Unexpected trade store call: " + method.getName());
        });
        var service = service(store);
        var decision = directionalDecision();
        var proposal = TradeProposal.from(
                new TradeProposalId("proposal_projection_service_test"),
                decision,
                NOW);

        var result = service.estimateTrade(
                "automation_projection_service_test",
                RUN_ID,
                decision,
                proposal,
                riskPolicy(),
                "AAPLUSDT");

        assertEquals(TradeAutomationStatus.ESTIMATED, result.status());
        assertEquals(List.of(), result.plannedOrderIds());
        assertEquals(TradeAutomationStatus.ESTIMATED, completedStatus.get());
        assertNotNull(storedProjection.get());
        assertEquals(DirectionalAction.BUY, storedProjection.get().side());
        assertEquals("AAPLUSDT", storedProjection.get().instrument().symbol().value());
    }

    @Test
    void uncertainSdbConsensusFailsClosedBeforeExecutionAi() {
        var savedDecision = new AtomicReference<TradeDecision>();
        var completedStatus = new AtomicReference<TradeAutomationStatus>();
        var tradeStore = proxy(TradeAutomationStore.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "findTerminal" -> Optional.empty();
            case "start" -> true;
            case "saveDecision" -> {
                savedDecision.set((TradeDecision) arguments[1]);
                yield null;
            }
            case "complete" -> {
                completedStatus.set((TradeAutomationStatus) arguments[1]);
                yield null;
            }
            case "fail" -> null;
            default -> throw new AssertionError(
                    "Execution AI and order planning must not run without SDB consensus: " + method.getName());
        });
        var version = sdbVersion();
        var context = new WorkflowExecutionContext(
                RUN_ID,
                WorkflowRunStatus.COMPLETED,
                "Analyze BTCUSDT",
                "{}",
                version,
                new ResearchMarketScope(
                        new InstrumentId("instrument_btc_sdb_test"),
                        ExchangeVenue.GATE,
                        ExchangeEnvironment.TESTNET,
                        "BTCUSDT",
                        300,
                        3600,
                        new BigDecimal("60000")));
        var debateId = new DebateId("debate_trade_sdb_test");
        var session = new DebateSession(
                debateId,
                RUN_ID,
                DebateStatus.COMPLETED,
                1,
                1,
                new WorkflowNodeId("node_social_choice"),
                NOW,
                NOW);
        var consensus = new AgentMessage(
                new AgentMessageId("message_trade_sdb_test"),
                debateId,
                RUN_ID,
                new WorkflowNodeId("node_social_choice"),
                "对称社会选择",
                0,
                3,
                AgentMessageType.CONSENSUS_RESULT,
                AgentMessageStatus.COMPLETED,
                new AgentMessageContent(
                        "未形成严格共识",
                        "正反顺序结果不一致",
                        new BigDecimal("0.25"),
                        List.of(),
                        List.of(),
                        List.of("ORDER_SENSITIVE"),
                        List.of(),
                        new ForecastSignal(
                                ForecastDirection.UNCERTAIN,
                                null,
                                null,
                                null,
                                null,
                                new BigDecimal("0.25"),
                                "社会选择未形成严格胜者",
                                List.of())),
                List.of(),
                NOW);
        var workflowStore = proxy(WorkflowExecutionStore.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "load" -> Optional.of(context);
            case "findDebate" -> Optional.of(session);
            case "messages" -> List.of(consensus);
            default -> throw new AssertionError("Unexpected workflow store call: " + method.getName());
        });
        var aiInvoker = new WorkflowAiInvoker(
                unused(AiCompletionGateway.class),
                unused(AiRuntimeBindingResolver.class),
                unused(AiInvocationAuditStore.class),
                unused(AiBudgetReservationStore.class),
                unused(WorkflowEventPublisher.class),
                unused(SortableIdGenerator.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var service = new TradeAutomationApplicationService(
                workflowStore,
                new AiExecutionPolicyExecutor(aiInvoker, Clock.fixed(NOW, ZoneOffset.UTC)),
                unused(TradeDecisionOutputParser.class),
                tradeStore,
                unused(PaperOrderExecutionUseCase.class),
                new MarginRiskEngine(),
                new EstimatedTradeEngine(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Runnable::run);

        var result = service.execute(RUN_ID).toCompletableFuture().join();

        assertEquals(TradeAutomationStatus.NO_ACTION, result.status());
        assertEquals(TradeAutomationStatus.NO_ACTION, completedStatus.get());
        var decision = (NonDirectionalTradeDecision) savedDecision.get();
        assertEquals(NonDirectionalAction.WATCH, decision.action());
        assertEquals("BTCUSDT", decision.symbol().value());
    }

    private static TradeAutomationApplicationService service(TradeAutomationStore store) {
        var workflowStore = proxy(WorkflowExecutionStore.class, (ignored, method, arguments) -> {
            if (method.getName().equals("load")) {
                return Optional.empty();
            }
            throw new AssertionError("Unexpected workflow store call: " + method.getName());
        });
        var aiInvoker = new WorkflowAiInvoker(
                unused(AiCompletionGateway.class),
                unused(AiRuntimeBindingResolver.class),
                unused(AiInvocationAuditStore.class),
                unused(AiBudgetReservationStore.class),
                unused(WorkflowEventPublisher.class),
                unused(SortableIdGenerator.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new TradeAutomationApplicationService(
                workflowStore,
                new AiExecutionPolicyExecutor(aiInvoker, Clock.fixed(NOW, ZoneOffset.UTC)),
                unused(TradeDecisionOutputParser.class),
                store,
                unused(PaperOrderExecutionUseCase.class),
                new MarginRiskEngine(),
                new EstimatedTradeEngine(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Runnable::run);
    }

    private static TradeAutomationStore retryStore(AtomicInteger startCalls, boolean restartAllowed) {
        var failed = new TradeAutomationResult(
                "automation_trade_retry_test",
                TradeAutomationStatus.FAILED,
                null,
                List.of(),
                List.of("Temporary execution failure"));
        return proxy(TradeAutomationStore.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "findTerminal" -> Optional.of(failed);
            case "start" -> {
                startCalls.incrementAndGet();
                yield restartAllowed;
            }
            case "fail" -> null;
            default -> throw new AssertionError("Unexpected trade store call: " + method.getName());
        });
    }

    private static Throwable rootCause(Throwable failure) {
        var current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static DirectionalTradeDecision directionalDecision() {
        return new DirectionalTradeDecision(
                new TradeDecisionId("decision_projection_service_test"),
                new InstrumentSymbol("AAPLUSDT"),
                DirectionalAction.BUY,
                new Confidence(new BigDecimal("0.82")),
                new Price(new BigDecimal("100")),
                new Price(new BigDecimal("110")),
                new Price(new BigDecimal("95")),
                List.of("test"),
                NOW);
    }

    private static ProjectionInstrumentSpec projectionInstrument() {
        return new ProjectionInstrumentSpec(
                new InstrumentId("instrument_bybit_aapl_projection"),
                ExchangeVenue.BYBIT,
                new InstrumentSymbol("AAPLUSDT"),
                BigDecimal.ONE,
                new BigDecimal("0.1"),
                new BigDecimal("0.1"),
                new BigDecimal("100"),
                Optional.of(new Price(new BigDecimal("100"))));
    }

    private static RiskPolicy riskPolicy() {
        return new RiskPolicy(
                "projection-service-test-v1",
                true,
                new BigDecimal("0.65"),
                new BigDecimal("5"),
                new BigDecimal("100"),
                new BigDecimal("20"),
                new BigDecimal("100"),
                3,
                new BigDecimal("0.10"),
                new BigDecimal("0.0006"),
                new BigDecimal("0.0005"),
                new BigDecimal("0.002"));
    }

    private static WorkflowDefinitionVersion sdbVersion() {
        var input = deterministicNode("node_input", WorkflowNodeType.INPUT, null, null);
        var agentA = sdbAgent("node_agent_a", "macro_role");
        var agentB = sdbAgent("node_agent_b", "risk_role");
        var decision = deterministicNode(
                "node_social_choice",
                WorkflowNodeType.SOCIAL_CHOICE,
                WorkflowOutputContract.CONSENSUS_RESULT,
                "schulze_social_choice");
        var output = deterministicNode("node_output", WorkflowNodeType.OUTPUT, null, null);
        return new WorkflowDefinitionVersion(
                new WorkflowVersionId("workflowversion_trade_sdb_test"),
                new WorkflowDefinitionId("workflow_trade_sdb_test"),
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
                "b".repeat(64),
                NOW,
                NOW,
                "test",
                List.of(input, agentA, agentB, decision, output),
                List.of(
                        edge("edge_input_a", input, agentA, WorkflowEdgeContextMode.INCLUDE),
                        edge("edge_input_b", input, agentB, WorkflowEdgeContextMode.INCLUDE),
                        edge("edge_a_choice", agentA, decision, WorkflowEdgeContextMode.EXCLUDE),
                        edge("edge_b_choice", agentB, decision, WorkflowEdgeContextMode.EXCLUDE),
                        edge("edge_choice_output", decision, output, WorkflowEdgeContextMode.INCLUDE)));
    }

    private static WorkflowNodeDefinition sdbAgent(String id, String logicalRoleKey) {
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
                "Return structured research.",
                "Analyze independently.",
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

    private static <T> T unused(Class<T> type) {
        return proxy(type, (ignored, method, arguments) -> {
            throw new AssertionError("Unexpected " + type.getSimpleName() + " call: " + method.getName());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static TradeExecutionAiStageConfig stage(TradeExecutionAiStage stage) {
        return new TradeExecutionAiStageConfig(
                stage,
                new AiModelBinding(
                        new AiProviderProfileId("provider_sub2api_default"),
                        "gpt-5.6-sol",
                        ReasoningEffort.MAX),
                null,
                "System prompt",
                "User prompt",
                4_096,
                300,
                new WorkflowRetryPolicy(3, Duration.ofSeconds(2)),
                true,
                0);
    }
}
