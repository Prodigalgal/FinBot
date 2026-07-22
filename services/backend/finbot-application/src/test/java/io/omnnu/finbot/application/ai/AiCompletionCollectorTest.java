package io.omnnu.finbot.application.ai;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.omnnu.finbot.application.operations.TaskCancellationContext;
import io.omnnu.finbot.application.operations.TaskCancellationToken;
import io.omnnu.finbot.domain.ai.AiInvocationId;
import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AiCompletionCollectorTest {
    @Test
    void taskCancellationCancelsTheStreamAndUnblocksTheInvocation() throws Exception {
        var cancelled = new CountDownLatch(1);
        var awaiting = new CountDownLatch(1);
        var token = new TaskCancellationToken();
        var request = new AiCompletionRequest(
                new AiInvocationId("invocation_cancel_test"),
                new WorkflowRunId("run_cancel_test"),
                new WorkflowNodeId("node_cancel_test"),
                new AiProviderProfileId("provider_cancel_test"),
                AiProtocol.RESPONSES,
                "model-cancel-test",
                ReasoningEffort.MAX,
                "System prompt",
                "User prompt",
                1024,
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                Instant.now().plus(Duration.ofMinutes(5)),
                "cancel-test-v1");
        var collector = new AiCompletionCollector(
                request,
                new NoOpAuditStore(),
                (runId, factory) -> null,
                Clock.systemUTC());
        collector.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long count) {
            }

            @Override
            public void cancel() {
                cancelled.countDown();
            }
        });

        try (var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            var invocation = executor.submit(() -> TaskCancellationContext.call(token, () -> {
                awaiting.countDown();
                return collector.await(
                            Instant.now(),
                            Duration.ofMinutes(5),
                            Duration.ofMinutes(5),
                            request.deadline());
            }));

            assertTrue(awaiting.await(1, TimeUnit.SECONDS));
            token.cancel();

            assertTrue(cancelled.await(1, TimeUnit.SECONDS));
            var failure = assertThrows(ExecutionException.class, () -> invocation.get(1, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
        }
    }

    private static final class NoOpAuditStore implements AiInvocationAuditStore {
        @Override
        public void start(AiInvocationStart start) {
        }

        @Override
        public void appendChunk(
                AiInvocationId invocationId,
                long sequence,
                String content,
                Instant occurredAt) {
        }

        @Override
        public void complete(AiInvocationCompletion completion) {
        }

        @Override
        public void fail(AiInvocationFailure failure) {
        }
    }
}
