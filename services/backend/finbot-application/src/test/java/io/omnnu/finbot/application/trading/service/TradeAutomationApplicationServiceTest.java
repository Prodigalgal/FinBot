package io.omnnu.finbot.application.trading.service;

import io.omnnu.finbot.application.trading.dto.StoredEstimatedTradeProjection;
import io.omnnu.finbot.application.trading.dto.PaperOrderReservationStatus;
import io.omnnu.finbot.application.trading.dto.PlannedOrder;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.application.ai.port.out.AiBudgetReservationStore;
import io.omnnu.finbot.application.ai.service.AiExecutionPolicyExecutor;
import io.omnnu.finbot.application.ai.port.out.AiCompletionGateway;
import io.omnnu.finbot.application.ai.port.out.AiInvocationAuditStore;
import io.omnnu.finbot.application.ai.port.out.AiRuntimeBindingResolver;
import io.omnnu.finbot.application.ai.service.WorkflowAiInvoker;
import io.omnnu.finbot.application.exchange.port.in.PaperOrderExecutionUseCase;
import io.omnnu.finbot.application.exchange.dto.ExchangeSubmissionStatus;
import io.omnnu.finbot.application.exchange.dto.PaperOrderExecutionResult;
import io.omnnu.finbot.application.market.dto.ResearchMarketScope;
import io.omnnu.finbot.application.shared.port.out.SortableIdGenerator;
import io.omnnu.finbot.application.workflow.dto.DebateSession;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.application.workflow.port.in.PrincipalReviewUseCase;
import io.omnnu.finbot.application.workflow.port.in.ExecutionPanelUseCase;
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
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.market.Price;
import io.omnnu.finbot.domain.oms.OrderId;
import io.omnnu.finbot.domain.research.ForecastDirection;
import io.omnnu.finbot.domain.research.ForecastSignal;
import io.omnnu.finbot.domain.risk.ProjectionInstrumentSpec;
import io.omnnu.finbot.domain.risk.RiskInstrumentSpec;
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
import java.util.concurrent.CompletableFuture;
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
    void unscopedWorkflowBlocksBeforeReadingDecisionOrCallingAi() {
        assertResearchScopeBlocksBeforeDecision(null);
    }

    @Test
    void liveResearchScopeCannotTriggerPaperTrading() {
        assertResearchScopeBlocksBeforeDecision(new ResearchMarketScope(
                demoScope().instrumentId(), ExchangeVenue.BYBIT, ExchangeEnvironment.LIVE,
                "AAPLUSDT", 3600, 86400, new BigDecimal("100")));
    }

    private static void assertResearchScopeBlocksBeforeDecision(ResearchMarketScope marketScope) {
        var completedStatus = new AtomicReference<TradeAutomationStatus>();
        var tradeStore = proxy(TradeAutomationStore.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "findTerminal" -> Optional.empty();
            case "recoverDurableOrders" -> false;
            case "start" -> true;
            case "complete" -> {
                completedStatus.set((TradeAutomationStatus) arguments[1]);
                yield null;
            }
            case "fail" -> null;
            default -> throw new AssertionError("Unscoped workflow must not plan an order: " + method.getName());
        });
        var workflow = new WorkflowExecutionContext(
                RUN_ID, WorkflowRunStatus.COMPLETED, "Analyze BTCUSDT", "{}", sdbVersion(), marketScope);
        var workflowStore = proxy(WorkflowExecutionStore.class, (ignored, method, arguments) -> {
            if (method.getName().equals("load")) {
                return Optional.of(workflow);
            }
            throw new AssertionError("Unscoped workflow must not read a trading decision");
        });

        var result = service(tradeStore, workflowStore).execute(RUN_ID).toCompletableFuture().join();

        assertEquals(TradeAutomationStatus.BLOCKED, result.status());
        assertEquals(TradeAutomationStatus.BLOCKED, completedStatus.get());
        assertEquals(List.of(), result.plannedOrderIds());
        assertTrue(result.reasons().getFirst().contains("单产品"));
    }

    @Test
    void decisionMustMatchThePersistedResearchSymbol() {
        assertTrue(TradeAutomationApplicationService.matchesResearchScope(
                directionalDecision(), demoScope()));
        var otherScope = new ResearchMarketScope(
                new InstrumentId("instrument_bybit_btc_test"), ExchangeVenue.BYBIT,
                ExchangeEnvironment.DEMO, "BTCUSDT", 3600, 86400,
                new BigDecimal("60000"));
        assertEquals(false, TradeAutomationApplicationService.matchesResearchScope(
                directionalDecision(), otherScope));
    }

    @Test
    void oneScopedAccountProducesOnePlannedOrder() {
        var planned = new AtomicReference<PlannedOrder>();
        var store = planningStore(List.of(executionInstrument("account_bybit_demo_first")),
                PaperOrderReservationStatus.RESERVED, planned);

        var result = service(store).planOrders(
                "automation_single_scoped_test", RUN_ID, directionalDecision(), demoScope(), null);

        assertEquals(TradeAutomationStatus.ORDER_PLANNED, result.status());
        assertEquals(1, result.plannedOrderIds().size());
        assertEquals(demoScope().instrumentId(), planned.get().instrumentId());
        assertEquals(ExchangeEnvironment.DEMO, planned.get().environment());
    }

    @Test
    void multipleEnabledAccountsBlockBeforeRiskAssessment() {
        var store = planningStore(List.of(
                executionInstrument("account_bybit_demo_first"),
                executionInstrument("account_bybit_demo_second")),
                PaperOrderReservationStatus.RESERVED, new AtomicReference<>());

        var result = service(store).planOrders(
                "automation_ambiguous_account_test", RUN_ID, directionalDecision(), demoScope(), null);

        assertEquals(TradeAutomationStatus.BLOCKED, result.status());
        assertEquals(List.of(), result.plannedOrderIds());
        assertTrue(result.reasons().getFirst().contains("多个启用的模拟账户"));
    }

    @Test
    void pausedLocalPaperCannotFallBackToExchangeExecutionForLiveResearch() {
        var store = proxy(TradeAutomationStore.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "saveProposal", "complete" -> null;
            case "activeRiskPolicy" -> riskPolicy();
            default -> throw new AssertionError("Paused LIVE research must not query private execution candidates: " + method.getName());
        });
        var live = new ResearchMarketScope(demoScope().instrumentId(), ExchangeVenue.BYBIT, ExchangeEnvironment.LIVE,
                "AAPLUSDT", 3600, 86400, new BigDecimal("100"));
        var result = service(store).planOrders("automation_paused_live_test", RUN_ID, directionalDecision(), live, null);
        assertEquals(TradeAutomationStatus.BLOCKED, result.status());
        assertEquals(List.of(), result.plannedOrderIds());
        assertTrue(result.reasons().getFirst().contains("LIVE"));
    }

    @Test
    void occupiedSymbolBlocksAfterRiskAssessmentWithoutAnOrder() {
        var planned = new AtomicReference<PlannedOrder>();
        var store = planningStore(List.of(executionInstrument("account_bybit_demo_first")),
                PaperOrderReservationStatus.SYMBOL_ALREADY_EXPOSED, planned);

        var result = service(store).planOrders(
                "automation_occupied_symbol_test", RUN_ID, directionalDecision(), demoScope(), null);

        assertEquals(TradeAutomationStatus.BLOCKED, result.status());
        assertEquals(List.of(), result.plannedOrderIds());
        assertTrue(result.reasons().getFirst().contains("已有持仓"));
        assertNotNull(planned.get());
    }

    @Test
    void interruptedPlanningResumesItsDurableOrderWithoutStartingAgain() {
        var orderId = new OrderId("order_recovered_paper_test");
        var lookupCount = new AtomicInteger();
        var submitted = new AtomicReference<List<OrderId>>();
        var planned = new TradeAutomationResult(
                "automation_recovered_paper_test", TradeAutomationStatus.ORDER_PLANNED,
                new TradeDecisionId("decision_recovered_paper_test"),
                List.of(orderId), List.of("recovered"));
        var store = proxy(TradeAutomationStore.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "findTerminal" -> lookupCount.incrementAndGet() == 1
                    ? Optional.empty() : Optional.of(planned);
            case "recoverDurableOrders" -> true;
            case "recordExecutionResults" -> null;
            default -> throw new AssertionError("Recovered order must not restart AI: " + method.getName());
        });
        PaperOrderExecutionUseCase orderExecution = orderIds -> {
            submitted.set(orderIds);
            return CompletableFuture.completedFuture(List.of(new PaperOrderExecutionResult(
                    orderId, ExchangeSubmissionStatus.ACKNOWLEDGED, "exchange-order", "accepted")));
        };

        var result = service(store, orderExecution).execute(RUN_ID).toCompletableFuture().join();

        assertEquals(TradeAutomationStatus.SUBMITTED, result.status());
        assertEquals(List.of(orderId), submitted.get());
        assertEquals(2, lookupCount.get());
    }

    @Test
    void recoveredSubmittedOrderDoesNotSubmitAgain() {
        var orderId = new OrderId("order_already_submitted_test");
        var planned = new TradeAutomationResult(
                "automation_already_submitted_test", TradeAutomationStatus.ORDER_PLANNED,
                new TradeDecisionId("decision_already_submitted_test"),
                List.of(orderId), List.of("planned"));
        var submitted = new TradeAutomationResult(
                "automation_already_submitted_test", TradeAutomationStatus.SUBMITTED,
                new TradeDecisionId("decision_already_submitted_test"),
                List.of(orderId), List.of("recovered"));
        var lookupCount = new AtomicInteger();
        var store = proxy(TradeAutomationStore.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "findTerminal" -> lookupCount.incrementAndGet() == 1
                    ? Optional.of(planned) : Optional.of(submitted);
            case "recoverDurableOrders" -> true;
            default -> throw new AssertionError("Submitted order must not be sent again: " + method.getName());
        });

        var result = service(store).execute(RUN_ID).toCompletableFuture().join();

        assertEquals(TradeAutomationStatus.SUBMITTED, result.status());
        assertEquals(2, lookupCount.get());
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
        var marketScope = new ResearchMarketScope(
                projectionInstrument().instrumentId(),
                ExchangeVenue.BYBIT,
                ExchangeEnvironment.DEMO,
                "AAPLUSDT",
                3600,
                86400,
                new BigDecimal("100"));
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
                marketScope);

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
            case "recoverDurableOrders" -> false;
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
                io.omnnu.finbot.domain.debate.DecisionPanelKey.RESEARCH,
                io.omnnu.finbot.domain.debate.DecisionPanelPurpose.RESEARCH,
                new io.omnnu.finbot.domain.debate.DecisionPanelInputHash("b".repeat(64)),
                new io.omnnu.finbot.domain.debate.DecisionPanelFrozenInput("{}"),
                DebateStatus.COMPLETED,
                1,
                1,
                new WorkflowNodeId("node_social_choice"),
                NOW,
                NOW,
                1);
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
                proxy(io.omnnu.finbot.application.paper.port.in.LocalPaperUseCase.class, (ignored, method, arguments) -> {
                    if (method.getName().equals("supports")) return false;
                    throw new AssertionError("Unexpected local paper call: " + method.getName());
                }),
                new MarginRiskEngine(),
                new EstimatedTradeEngine(),
                unused(PrincipalReviewUseCase.class),
                unused(ExecutionPanelUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Runnable::run);

        var result = service.execute(RUN_ID).toCompletableFuture().join();

        assertEquals(TradeAutomationStatus.NO_ACTION, result.status());
        assertEquals(TradeAutomationStatus.NO_ACTION, completedStatus.get());
        var decision = (NonDirectionalTradeDecision) savedDecision.get();
        assertEquals(NonDirectionalAction.WATCH, decision.action());
        assertEquals("BTCUSDT", decision.symbol().value());
    }

    @Test
    void principalReviewRejectionFailsClosedBeforeExecution() {
        var savedDecision = new java.util.concurrent.atomic.AtomicReference<TradeDecision>();
        var completedStatus = new java.util.concurrent.atomic.AtomicReference<TradeAutomationStatus>();
        var tradeStore = proxy(TradeAutomationStore.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "findTerminal" -> Optional.empty();
            case "recoverDurableOrders" -> false;
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
                    "Execution must not run if principal review rejects: " + method.getName());
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
                io.omnnu.finbot.domain.debate.DecisionPanelKey.RESEARCH,
                io.omnnu.finbot.domain.debate.DecisionPanelPurpose.RESEARCH,
                new io.omnnu.finbot.domain.debate.DecisionPanelInputHash("b".repeat(64)),
                new io.omnnu.finbot.domain.debate.DecisionPanelFrozenInput("{}"),
                DebateStatus.COMPLETED,
                1,
                1,
                new WorkflowNodeId("node_decision"),
                NOW,
                NOW,
                1);
        var consensus = new AgentMessage(
                new AgentMessageId("message_trade_sdb_consensus"),
                debateId,
                RUN_ID,
                new WorkflowNodeId("node_decision"),
                "对称社会选择",
                0,
                3,
                AgentMessageType.CONSENSUS_RESULT,
                AgentMessageStatus.COMPLETED,
                new AgentMessageContent(
                        "研究共识达成",
                        "看涨趋势确立",
                        new BigDecimal("0.85"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        new ForecastSignal(
                                ForecastDirection.UP,
                                new BigDecimal("60000"),
                                new BigDecimal("59000"),
                                new BigDecimal("63000"),
                                new BigDecimal("58000"),
                                new BigDecimal("0.85"),
                                "趋势看涨",
                                List.of("ref_test"))),
                List.of(),
                NOW);
        var workflowStore = proxy(WorkflowExecutionStore.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "load" -> Optional.of(context);
            case "findDebate" -> Optional.of(session);
            case "messages" -> List.of(consensus);
            default -> throw new AssertionError("Unexpected workflow store call: " + method.getName());
        });
        var principalReviewUseCase = proxy(PrincipalReviewUseCase.class, (ignored, method, arguments) -> {
            var rejectedDecision = new io.omnnu.finbot.domain.debate.PrincipalReviewDecision(
                    io.omnnu.finbot.domain.debate.PrincipalReviewAction.REJECT,
                    "驳回摘要",
                    "链上大额异动导致反转风险严重，不可开多",
                    null,
                    null,
                    null,
                    List.of(),
                    List.of("链上大额异动导致反转风险严重，不可开多"),
                    List.of("流动性断崖反例"));
            var dummyConsensus = new io.omnnu.finbot.domain.consensus.ConsensusDecision(
                    new io.omnnu.finbot.domain.consensus.ConsensusDecisionId("decision_" + "0".repeat(40)),
                    debateId,
                    io.omnnu.finbot.domain.consensus.SchulzeOutcome.unsuccessful(io.omnnu.finbot.domain.consensus.ConsensusStatus.NO_STRICT_WINNER, List.of(), 0),
                    null,
                    "{}",
                    "{}",
                    "[]",
                    null,
                    "rejected",
                    "0".repeat(64),
                    NOW);
            var reviewResult = new io.omnnu.finbot.application.workflow.dto.PrincipalReviewResult(
                    session,
                    consensus,
                    dummyConsensus,
                    rejectedDecision,
                    false,
                    false);
            return reviewResult;
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
                proxy(io.omnnu.finbot.application.paper.port.in.LocalPaperUseCase.class, (ignored, method, arguments) -> {
                    if (method.getName().equals("supports")) return false;
                    throw new AssertionError("Unexpected local paper call: " + method.getName());
                }),
                new MarginRiskEngine(),
                new EstimatedTradeEngine(),
                principalReviewUseCase,
                unused(ExecutionPanelUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Runnable::run);

        var result = service.execute(RUN_ID).toCompletableFuture().join();

        assertEquals(TradeAutomationStatus.NO_ACTION, result.status());
        assertEquals(TradeAutomationStatus.NO_ACTION, completedStatus.get());
        var decision = (NonDirectionalTradeDecision) savedDecision.get();
        assertEquals(NonDirectionalAction.WATCH, decision.action());
        assertTrue(decision.rationale().getFirst().contains("链上大额异动导致反转风险严重"));
    }

    private static TradeAutomationApplicationService service(TradeAutomationStore store) {
        var workflowStore = proxy(WorkflowExecutionStore.class, (ignored, method, arguments) -> {
            if (method.getName().equals("load")) {
                return Optional.empty();
            }
            throw new AssertionError("Unexpected workflow store call: " + method.getName());
        });
        return service(store, workflowStore, unused(PaperOrderExecutionUseCase.class));
    }

    private static TradeAutomationApplicationService service(
            TradeAutomationStore store, WorkflowExecutionStore workflowStore) {
        return service(store, workflowStore, unused(PaperOrderExecutionUseCase.class));
    }

    private static TradeAutomationApplicationService service(
            TradeAutomationStore store, PaperOrderExecutionUseCase orderExecution) {
        return service(store, unused(WorkflowExecutionStore.class), orderExecution);
    }

    private static TradeAutomationApplicationService service(
            TradeAutomationStore store,
            WorkflowExecutionStore workflowStore,
            PaperOrderExecutionUseCase orderExecution) {
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
                orderExecution,
                proxy(io.omnnu.finbot.application.paper.port.in.LocalPaperUseCase.class, (ignored, method, arguments) -> {
                    if (method.getName().equals("supports")) return false;
                    throw new AssertionError("Unexpected local paper call: " + method.getName());
                }),
                new MarginRiskEngine(),
                new EstimatedTradeEngine(),
                unused(PrincipalReviewUseCase.class),
                unused(ExecutionPanelUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Runnable::run);
    }

    private static TradeAutomationStore planningStore(
            List<RiskInstrumentSpec> candidates,
            PaperOrderReservationStatus reservationStatus,
            AtomicReference<PlannedOrder> plannedOrder) {
        return proxy(TradeAutomationStore.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "saveProposal", "saveRiskAssessment" -> null;
            case "activeRiskPolicy" -> riskPolicy();
            case "executionCandidates" -> {
                assertEquals(demoScope(), arguments[0]);
                assertEquals(NOW.minusSeconds(900), arguments[1]);
                assertEquals(NOW.minusSeconds(7200), arguments[2]);
                assertEquals(NOW, arguments[3]);
                yield candidates;
            }
            case "reserveApprovedIntentAndOrder" -> {
                plannedOrder.set((PlannedOrder) arguments[1]);
                assertEquals(3, arguments[2]);
                yield reservationStatus;
            }
            case "complete" -> null;
            default -> throw new AssertionError("Unexpected trade store call: " + method.getName());
        });
    }

    private static ResearchMarketScope demoScope() {
        return new ResearchMarketScope(
                new InstrumentId("instrument_bybit_aapl_projection"),
                ExchangeVenue.BYBIT, ExchangeEnvironment.DEMO,
                "AAPLUSDT", 3600, 86400, new BigDecimal("100"));
    }

    private static RiskInstrumentSpec executionInstrument(String accountId) {
        return new RiskInstrumentSpec(
                demoScope().instrumentId(), new ExchangeAccountId(accountId),
                ExchangeVenue.BYBIT, ExchangeEnvironment.DEMO,
                new InstrumentSymbol("AAPLUSDT"), BigDecimal.ONE,
                new BigDecimal("0.1"), new BigDecimal("0.1"),
                new BigDecimal("100"), new Price(new BigDecimal("100")), 0);
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
            case "recoverDurableOrders" -> false;
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
