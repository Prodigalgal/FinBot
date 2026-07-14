package io.omnnu.finbot.application.operations;

import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import java.util.concurrent.CompletionStage;

public interface BackgroundTaskHandler {
    BackgroundTaskType taskType();

    CompletionStage<Void> handle(BackgroundTask task);
}
