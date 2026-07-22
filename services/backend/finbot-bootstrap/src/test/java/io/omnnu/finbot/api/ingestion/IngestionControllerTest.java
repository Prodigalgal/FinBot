package io.omnnu.finbot.api.ingestion;

import io.omnnu.finbot.api.ingestion.controller.IngestionController;
import io.omnnu.finbot.api.ingestion.dto.CollectSourceRequest;
import io.omnnu.finbot.api.ingestion.dto.TestSourceRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.omnnu.finbot.application.ingestion.port.in.IngestionUseCase;
import io.omnnu.finbot.application.operations.dto.BackgroundTask;
import io.omnnu.finbot.application.operations.service.BackgroundTaskCoordinator;
import io.omnnu.finbot.application.operations.dto.EnqueueTaskCommand;
import io.omnnu.finbot.application.operations.dto.IngestionTaskPayload;
import io.omnnu.finbot.application.shared.service.IdempotencyKeys;
import io.omnnu.finbot.domain.operations.BackgroundTaskId;
import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IngestionControllerTest {
    @Test
    void enqueuesLongSourceIdWithBoundedSourceScopedIdempotencyKey() {
        var useCase = mock(IngestionUseCase.class);
        var capturedCommand = new AtomicReference<EnqueueTaskCommand>();
        var coordinator = capturingCoordinator(capturedCommand);
        var controller = new IngestionController(useCase, coordinator);
        var sourceId = "source_searxng_news_search";
        var clientKey = "manual-smoke-01";

        var response = controller.collect(
                sourceId,
                clientKey,
                new CollectSourceRequest("AI semiconductor finance markets", null));

        assertEquals(202, response.getStatusCode().value());
        var command = capturedCommand.get();
        assertEquals(
                IdempotencyKeys.scoped("manual-ingestion", sourceId + ':' + clientKey),
                command.idempotencyKey());
        var payload = (IngestionTaskPayload) command.payload();
        assertEquals(sourceId, payload.sourceId().value());
        assertEquals("AI semiconductor finance markets", payload.query());
    }

    @Test
    void enqueuesSourceTestInsteadOfWaitingForExternalCollection() {
        var useCase = mock(IngestionUseCase.class);
        var capturedCommand = new AtomicReference<EnqueueTaskCommand>();
        var controller = new IngestionController(
                useCase,
                capturingCoordinator(capturedCommand));
        var sourceId = "source_searxng_international_news_search";
        var clientKey = "source-test-smoke-01";

        var response = controller.testSource(
                sourceId,
                clientKey,
                new TestSourceRequest("global markets breaking news"));

        assertEquals(202, response.getStatusCode().value());
        var command = capturedCommand.get();
        assertEquals(
                IdempotencyKeys.scoped("source-test", sourceId + ':' + clientKey),
                command.idempotencyKey());
        assertEquals(80, command.priority());
        var payload = (IngestionTaskPayload) command.payload();
        assertEquals(sourceId, payload.sourceId().value());
        assertNull(payload.workflowRunId());
        assertEquals("global markets breaking news", payload.query());
    }

    private static BackgroundTaskCoordinator capturingCoordinator(
            AtomicReference<EnqueueTaskCommand> capturedCommand) {
        var coordinator = mock(BackgroundTaskCoordinator.class);
        when(coordinator.enqueue(any())).thenAnswer(invocation -> {
            var command = invocation.<EnqueueTaskCommand>getArgument(0);
            capturedCommand.set(command);
            var now = Instant.parse("2026-07-18T16:00:00Z");
            return new BackgroundTask(
                    new BackgroundTaskId("task_01j0000000001"),
                    command.taskType(),
                    BackgroundTaskStatus.PENDING,
                    command.priority(),
                    command.idempotencyKey(),
                    command.payload(),
                    0,
                    command.maximumAttempts(),
                    now,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    now,
                    now);
        });
        return coordinator;
    }
}
