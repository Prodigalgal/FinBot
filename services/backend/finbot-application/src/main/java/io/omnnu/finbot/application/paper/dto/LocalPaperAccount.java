package io.omnnu.finbot.application.paper.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record LocalPaperAccount(String accountId, String currency, String executionMode,
                                BigDecimal initialBalance, BigDecimal cashBalance, BigDecimal equity,
                                BigDecimal availableBalance, BigDecimal reservedMargin,
                                BigDecimal unrealizedPnl, BigDecimal realizedPnl,
                                BigDecimal feesUsdt, BigDecimal fundingUsdt,
                                boolean ordersEnabled, int pendingCount, int openCount,
                                Instant acceptProjectionsAfter, Instant lastCheckedAt, long version) {
}
