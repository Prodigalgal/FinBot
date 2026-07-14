package io.omnnu.finbot.application.exchange;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import java.time.Instant;

@FunctionalInterface
public interface ExchangeAccountGateway {
    ExchangeAccountSyncBatch synchronize(
            ExchangeAccountId accountId,
            Instant fromInclusive,
            Instant toExclusive);
}
