package io.omnnu.finbot.application.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TradingAccountsOverview(
        TradingTimeRange range,
        String currency,
        BigDecimal totalEquity,
        BigDecimal totalAvailableBalance,
        BigDecimal totalMarginBalance,
        BigDecimal totalUnrealizedPnl,
        BigDecimal totalRealizedPnl,
        List<AccountOverviewItem> accounts,
        Instant generatedAt) {
    public TradingAccountsOverview {
        accounts = List.copyOf(accounts);
    }
}
