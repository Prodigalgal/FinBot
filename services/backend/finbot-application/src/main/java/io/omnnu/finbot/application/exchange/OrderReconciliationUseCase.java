package io.omnnu.finbot.application.exchange;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface OrderReconciliationUseCase {
    CompletionStage<OrderReconciliationResult> reconcile(ExchangeAccountId accountId);
}
