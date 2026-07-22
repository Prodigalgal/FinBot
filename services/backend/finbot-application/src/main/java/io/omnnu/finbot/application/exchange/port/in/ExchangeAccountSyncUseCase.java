package io.omnnu.finbot.application.exchange.port.in;

import io.omnnu.finbot.application.exchange.dto.ExchangeAccountSyncResult;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ExchangeAccountSyncUseCase {
    CompletionStage<ExchangeAccountSyncResult> synchronize(ExchangeAccountId accountId);
}
