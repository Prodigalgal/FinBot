package io.omnnu.finbot.application.exchange.port.in;

import io.omnnu.finbot.application.exchange.dto.OrderReconciliationResult;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface OrderReconciliationUseCase {
    CompletionStage<OrderReconciliationResult> reconcile(ExchangeAccountId accountId);
}
