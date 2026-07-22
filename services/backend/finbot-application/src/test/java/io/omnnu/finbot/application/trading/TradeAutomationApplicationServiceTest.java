package io.omnnu.finbot.application.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.omnnu.finbot.application.ai.AiBudgetReservationStore;
import io.omnnu.finbot.application.ai.AiExecutionPolicyExecutor;
import io.omnnu.finbot.application.ai.AiCompletionGateway;
import io.omnnu.finbot.application.ai.AiInvocationAuditStore;
import io.omnnu.finbot.application.ai.AiRuntimeBindingResolver;
import io.omnnu.finbot.application.ai.WorkflowAiInvoker;
import io.omnnu.finbot.application.exchange.PaperOrderExecutionUseCase;
import io.omnnu.finbot.application.shared.SortableIdGenerator;
import io.omnnu.finbot.application.workflow.WorkflowEventPublisher;
import io.omnnu.finbot.application.workflow.WorkflowExecutionStore;
import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.market.Price;
import io.omnnu.finbot.domain.risk.ProjectionInstrumentSpec;
import io.omnnu.finbot.domain.risk.RiskPolicy;
import io.omnnu.finbot.domain.risk.MarginRiskEngine;
import io.omnnu.finbot.domain.risk.EstimatedTradeEngine;
import io.omnnu.finbot.domain.trading.Confidence;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import io.omnnu.finbot.domain.trading.DirectionalTradeDecision;
import io.omnnu.finbot.domain.trading.TradeDecisionId;
import io.omnnu.finbot.domain.trading.TradeProposal;
import io.omnnu.finbot.domain.trading.TradeProposalId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import io.omnnu.finbot.domain.workflow.WorkflowOutputContract;
import io.omnnu.finbot.domain.workflow.WorkflowRetryPolicy;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
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
