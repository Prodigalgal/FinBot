package io.omnnu.finbot.application.operations;

import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import java.util.concurrent.CompletionStage;

public interface BackgroundTaskHandler {
    BackgroundTaskType taskType();

    CompletionStage<Void> handle(BackgroundTask task);

    default CompletionStage<Void> handle(
            BackgroundTask task,
            TaskCancellationToken cancellationToken) {
        return TaskCancellationContext.call(cancellationToken, () -> handle(task));
    }
}
