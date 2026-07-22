package io.omnnu.finbot.operations.handler;

import io.omnnu.finbot.application.ingestion.port.in.IngestionUseCase;
import io.omnnu.finbot.application.operations.dto.BackgroundTask;
import io.omnnu.finbot.application.operations.port.in.BackgroundTaskHandler;
import io.omnnu.finbot.application.operations.dto.IngestionTaskPayload;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.springframework.stereotype.Component;

@Component
public final class IngestionTaskHandler implements BackgroundTaskHandler {
    private final IngestionUseCase ingestionUseCase;

    public IngestionTaskHandler(IngestionUseCase ingestionUseCase) {
        this.ingestionUseCase = Objects.requireNonNull(ingestionUseCase, "ingestionUseCase");
    }

    @Override
    public BackgroundTaskType taskType() {
        return BackgroundTaskType.INGESTION;
    }

    @Override
    public CompletionStage<Void> handle(BackgroundTask task) {
        if (!(task.payload() instanceof IngestionTaskPayload payload)) {
            throw new IllegalArgumentException("Ingestion task has an invalid payload");
        }
        return ingestionUseCase.collectSource(
                        payload.workflowRunId(),
                        payload.sourceId(),
                        payload.query())
                .thenApply(ignored -> null);
    }
}
