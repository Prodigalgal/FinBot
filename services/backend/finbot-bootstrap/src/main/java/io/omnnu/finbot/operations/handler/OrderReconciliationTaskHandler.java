package io.omnnu.finbot.operations.handler;

import io.omnnu.finbot.application.exchange.port.in.OrderReconciliationUseCase;
import io.omnnu.finbot.application.operations.dto.AccountTaskPayload;
import io.omnnu.finbot.application.operations.dto.BackgroundTask;
import io.omnnu.finbot.application.operations.port.in.BackgroundTaskHandler;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.springframework.stereotype.Component;

@Component
public final class OrderReconciliationTaskHandler implements BackgroundTaskHandler {
    private final OrderReconciliationUseCase reconciliationUseCase;

    public OrderReconciliationTaskHandler(OrderReconciliationUseCase reconciliationUseCase) {
        this.reconciliationUseCase = Objects.requireNonNull(
                reconciliationUseCase,
                "reconciliationUseCase");
    }

    @Override
    public BackgroundTaskType taskType() {
        return BackgroundTaskType.ORDER_RECONCILIATION;
    }

    @Override
    public CompletionStage<Void> handle(BackgroundTask task) {
        if (!(task.payload() instanceof AccountTaskPayload payload)) {
            throw new IllegalArgumentException("Order reconciliation task has an invalid payload");
        }
        return reconciliationUseCase.reconcile(payload.accountId()).thenApply(ignored -> null);
    }
}
