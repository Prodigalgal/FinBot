package io.omnnu.finbot.operations.handler;

import io.omnnu.finbot.operations.runtime.UserSelectedResearchRunner;

import static org.junit.jupiter.api.Assertions.*;
import io.omnnu.finbot.application.operations.dto.ResearchTaskMode;
import io.omnnu.finbot.application.operations.service.TaskCancellationContext;
import io.omnnu.finbot.application.operations.service.TaskCancellationToken;
import io.omnnu.finbot.application.research.dto.ResearchPipelineRequest;
import io.omnnu.finbot.application.workflow.dto.StartWorkflowCommand;
import io.omnnu.finbot.application.workflow.dto.StartWorkflowResult;
import io.omnnu.finbot.domain.workflow.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class UserSelectedResearchRunnerTest {
    @Test void waitsForOneProductBeforeStartingTheNext() {
        var first = new CompletableFuture<StartWorkflowResult>();
        var calls = new AtomicInteger();
        var runner = new UserSelectedResearchRunner(request -> calls.incrementAndGet() == 1
                ? first : CompletableFuture.completedFuture(result()));
        var completed = runner.execute(List.of(request("first"), request("second"))).toCompletableFuture();
        assertEquals(1, calls.get());
        assertFalse(completed.isDone());
        first.complete(result());
        completed.join();
        assertEquals(2, calls.get());
    }

    @Test void continuesAfterSynchronousOrAsynchronousFailureAndReportsBoth() {
        var calls = new AtomicInteger();
        var runner = new UserSelectedResearchRunner(request -> {
            if (calls.incrementAndGet() == 1) throw new IllegalArgumentException("first failed");
            return CompletableFuture.failedFuture(new IllegalStateException("second failed"));
        });
        var failure = assertThrows(CompletionException.class,
                () -> runner.execute(List.of(request("first"), request("second"))).toCompletableFuture().join());
        assertEquals(2, calls.get());
        assertEquals("first failed", failure.getCause().getCause().getMessage());
        assertEquals("second failed", failure.getCause().getSuppressed()[0].getMessage());
    }

    @Test void cancellationBetweenProductsStopsTheSequenceEvenOnAnotherCompletionThread() throws Exception {
        var first = new CompletableFuture<StartWorkflowResult>();
        var calls = new AtomicInteger();
        var cancellation = new TaskCancellationToken();
        var runner = new UserSelectedResearchRunner(request -> {
            assertSame(cancellation, TaskCancellationContext.current().orElseThrow());
            calls.incrementAndGet();
            return first;
        });
        var completed = TaskCancellationContext.call(cancellation,
                () -> runner.execute(List.of(request("first"), request("second")))).toCompletableFuture();
        cancellation.cancel();
        CompletableFuture.runAsync(() -> first.complete(result())).join();
        assertThrows(CompletionException.class, completed::join);
        assertEquals(1, calls.get());
    }

    private static ResearchPipelineRequest request(String key) {
        return new ResearchPipelineRequest(new StartWorkflowCommand(WorkflowType.INSTANT_RESEARCH,
                WorkflowTrigger.MANUAL, null, "user selected product", key), ResearchTaskMode.STANDARD, 1, 3);
    }
    private static StartWorkflowResult result() {
        return new StartWorkflowResult(new WorkflowRunId("run_test_selected"),
                new WorkflowEventId("event_test_selected"), Instant.parse("2026-10-04T00:00:00Z"));
    }
}
