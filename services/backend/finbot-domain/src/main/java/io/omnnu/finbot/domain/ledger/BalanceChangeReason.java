package io.omnnu.finbot.domain.ledger;

public enum BalanceChangeReason {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER,
    TRADE,
    FUNDING,
    FEE,
    SETTLEMENT,
    ADJUSTMENT,
    SNAPSHOT
}
