package io.omnnu.finbot.application.ledger.dto;

import io.omnnu.finbot.application.ledger.dto.LedgerValidation;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.LedgerFactId;
import io.omnnu.finbot.domain.market.Money;
import java.time.Instant;
import java.util.Objects;

public record AccountSnapshotFact(
        LedgerFactId snapshotId,
        ExchangeAccountId accountId,
        String sourceEventId,
        Money equity,
        Money availableBalance,
        Money marginBalance,
        Money unrealizedPnl,
        Instant occurredAt,
        Instant receivedAt) {
    public AccountSnapshotFact {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(accountId, "accountId");
        sourceEventId = requireSourceEventId(sourceEventId);
        requireSameCurrency(equity, availableBalance, marginBalance, unrealizedPnl);
        LedgerValidation.nonNegative(equity.amount(), "equity");
        LedgerValidation.nonNegative(availableBalance.amount(), "availableBalance");
        LedgerValidation.nonNegative(marginBalance.amount(), "marginBalance");
        LedgerValidation.requireTimeline(occurredAt, receivedAt);
    }

    private static String requireSourceEventId(String value) {
        var normalized = Objects.requireNonNull(value, "sourceEventId").strip();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw new IllegalArgumentException("sourceEventId is invalid");
        }
        return normalized;
    }

    private static void requireSameCurrency(Money first, Money... remaining) {
        Objects.requireNonNull(first, "equity");
        for (var money : remaining) {
            if (!first.currency().equals(Objects.requireNonNull(money, "money").currency())) {
                throw new IllegalArgumentException("Account snapshot currencies must match");
            }
        }
    }
}
