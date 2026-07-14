package io.omnnu.finbot.application.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.omnnu.finbot.application.ai.AiBudgetReservationStore;
import io.omnnu.finbot.application.ai.AiCompletionGateway;
import io.omnnu.finbot.application.ai.AiInvocationAuditStore;
import io.omnnu.finbot.application.ai.AiProviderProtocolResolver;
import io.omnnu.finbot.application.ai.WorkflowAiInvoker;
import io.omnnu.finbot.application.exchange.PaperOrderExecutionUseCase;
import io.omnnu.finbot.application.shared.SortableIdGenerator;
import io.omnnu.finbot.application.workflow.WorkflowEventPublisher;
import io.omnnu.finbot.application.workflow.WorkflowExecutionStore;
import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.risk.MarginRiskEngine;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import io.omnnu.finbot.domain.workflow.WorkflowOutputContract;
import io.omnnu.finbot.domain.workflow.WorkflowRetryPolicy;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
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

    private static TradeAutomationApplicationService service(TradeAutomationStore store) {
        var workflowStore = proxy(WorkflowExecutionStore.class, (ignored, method, arguments) -> {
            if (method.getName().equals("load")) {
                return Optional.empty();
            }
            throw new AssertionError("Unexpected workflow store call: " + method.getName());
        });
        var aiInvoker = new WorkflowAiInvoker(
                unused(AiCompletionGateway.class),
                unused(AiProviderProtocolResolver.class),
                unused(AiInvocationAuditStore.class),
                unused(AiBudgetReservationStore.class),
                unused(WorkflowEventPublisher.class),
                unused(SortableIdGenerator.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new TradeAutomationApplicationService(
                workflowStore,
                aiInvoker,
                unused(TradeDecisionOutputParser.class),
                store,
                unused(PaperOrderExecutionUseCase.class),
                new MarginRiskEngine(),
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
