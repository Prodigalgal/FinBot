package io.omnnu.finbot.operations.handler;

import io.omnnu.finbot.application.exchange.port.in.ExchangeAccountSyncUseCase;
import io.omnnu.finbot.application.operations.dto.AccountTaskPayload;
import io.omnnu.finbot.application.operations.dto.BackgroundTask;
import io.omnnu.finbot.application.operations.port.in.BackgroundTaskHandler;
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
