package io.omnnu.finbot.domain.ledger;

public enum ExchangeOrderStatus {
    NEW,
    SUBMITTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED,
    EXPIRED,
    UNKNOWN
}
