package io.omnnu.finbot.operations;

import io.omnnu.finbot.application.exchange.ExchangeAccountSyncUseCase;
import io.omnnu.finbot.application.operations.AccountTaskPayload;
import io.omnnu.finbot.application.operations.BackgroundTask;
import io.omnnu.finbot.application.operations.BackgroundTaskHandler;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.springframework.stereotype.Component;

@Component
public final class AccountSyncTaskHandler implements BackgroundTaskHandler {
    private final ExchangeAccountSyncUseCase syncUseCase;

    public AccountSyncTaskHandler(ExchangeAccountSyncUseCase syncUseCase) {
        this.syncUseCase = Objects.requireNonNull(syncUseCase, "syncUseCase");
    }

    @Override
    public BackgroundTaskType taskType() {
        return BackgroundTaskType.ACCOUNT_SYNC;
    }

    @Override
    public CompletionStage<Void> handle(BackgroundTask task) {
        if (!(task.payload() instanceof AccountTaskPayload payload)) {
            throw new IllegalArgumentException("Account sync task has an invalid payload");
        }
        return syncUseCase.synchronize(payload.accountId()).thenApply(ignored -> null);
    }
}
