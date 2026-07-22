package io.omnnu.finbot.application.operations.port.in;

import io.omnnu.finbot.application.operations.dto.BackgroundTask;
import io.omnnu.finbot.application.operations.service.TaskCancellationContext;
import io.omnnu.finbot.application.operations.service.TaskCancellationToken;

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
