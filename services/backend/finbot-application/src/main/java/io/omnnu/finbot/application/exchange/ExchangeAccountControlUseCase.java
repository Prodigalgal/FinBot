package io.omnnu.finbot.application.exchange;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;

@FunctionalInterface
public interface ExchangeAccountControlUseCase {
    ExchangeAccountControl setEnabled(
            ExchangeAccountId accountId,
            boolean enabled,
            long expectedVersion);
}
