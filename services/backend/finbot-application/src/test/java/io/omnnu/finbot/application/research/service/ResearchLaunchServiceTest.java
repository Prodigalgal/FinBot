package io.omnnu.finbot.application.research.service;

import static org.junit.jupiter.api.Assertions.*;
import io.omnnu.finbot.application.market.dto.MarketAnalysisScope;
import io.omnnu.finbot.application.operations.dto.BackgroundTask;
import io.omnnu.finbot.application.operations.dto.InstantResearchTaskPayload;
import io.omnnu.finbot.application.operations.dto.ResearchTaskMode;
import io.omnnu.finbot.application.operations.port.out.BackgroundTaskStore;
import io.omnnu.finbot.application.operations.service.BackgroundTaskCoordinator;
import io.omnnu.finbot.application.research.dto.ResearchExecutionScope;
import io.omnnu.finbot.application.workflow.dto.StartWorkflowCommand;
import io.omnnu.finbot.application.workflow.dto.StartWorkflowResult;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.workflow.*;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ResearchLaunchServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static final StartWorkflowCommand COMMAND = new StartWorkflowCommand(WorkflowType.INSTANT_RESEARCH,
            WorkflowTrigger.MANUAL, null, "分析指定商品", "frozen-scope-test");
    private static final MarketAnalysisScope BTC = new MarketAnalysisScope(new InstrumentId("instrument_bybit_btcusdt"),
            "BTCUSDT", ExchangeVenue.BYBIT, ExchangeEnvironment.LIVE, 3600, 86400);

    @Test void freezesOneSelectedProductBeforeAsynchronousAcceptance() {
        var scopes = new AtomicReference<>(List.of(BTC));
        var accepted = new CompletableFuture<StartWorkflowResult>();
        var service = new ResearchLaunchService(command -> accepted, coordinator(), run -> {}, scopes::get);
        var launch = service.launch(COMMAND, "task-frozen-scope", ResearchTaskMode.STANDARD);
        scopes.set(List.of());
        accepted.complete(started());
        var payload = (InstantResearchTaskPayload) launch.toCompletableFuture().join().task().payload();
        assertEquals(BTC, payload.marketAnalysisScope());
        assertEquals(ResearchExecutionScope.FULL, payload.executionScope());
        assertEquals(COMMAND.idempotencyKey(), payload.workflowIdempotencyKey());
    }

    @Test void emptySelectionRejectsBeforeCreatingAWorkflow() {
        var service = new ResearchLaunchService(command -> { throw new AssertionError("No workflow should be accepted"); },
                coordinator(), run -> {}, List::of);
        assertThrows(IllegalArgumentException.class, () -> service.launch(COMMAND, "empty-selection", ResearchTaskMode.STANDARD));
    }

    @Test void analysisChatRemainsUnscopedAndAnalysisOnly() {
        var service = new ResearchLaunchService(command -> CompletableFuture.completedFuture(started()), coordinator(), run -> {},
                () -> { throw new AssertionError("Chat should not consult trading products"); });
        var payload = (InstantResearchTaskPayload) service.launchAnalysis(COMMAND, "analysis-only").toCompletableFuture().join().task().payload();
        assertNull(payload.marketAnalysisScope());
        assertEquals(ResearchExecutionScope.ANALYSIS_ONLY, payload.executionScope());
    }

    private static BackgroundTaskCoordinator coordinator() {
        var store = (BackgroundTaskStore) Proxy.newProxyInstance(BackgroundTaskStore.class.getClassLoader(),
                new Class<?>[]{BackgroundTaskStore.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("enqueue")) return (BackgroundTask) arguments[0];
                    throw new AssertionError("Unexpected task operation: " + method.getName());
                });
        return new BackgroundTaskCoordinator(store, prefix -> prefix + "scope_test001", Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static StartWorkflowResult started() {
        return new StartWorkflowResult(new WorkflowRunId("run_scope_test001"), new WorkflowEventId("event_scope_test001"), NOW);
    }
}
