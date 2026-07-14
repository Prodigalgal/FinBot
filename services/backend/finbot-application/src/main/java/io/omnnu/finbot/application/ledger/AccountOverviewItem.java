package io.omnnu.finbot.application.ledger;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import java.math.BigDecimal;
import java.time.Instant;

public record AccountOverviewItem(
        ExchangeAccountId accountId,
        ExchangeVenue exchange,
        ExchangeEnvironment environment,
        String displayName,
        String proxyRoute,
        boolean enabled,
        long version,
        boolean credentialConfigured,
        AccountDataStatus dataStatus,
        String currency,
        BigDecimal equity,
        BigDecimal availableBalance,
        BigDecimal marginBalance,
        BigDecimal unrealizedPnl,
        BigDecimal realizedPnl,
        int openPositionCount,
        Instant snapshotAt) {
}
