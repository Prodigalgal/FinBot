package io.omnnu.finbot.domain.oms;

public enum OrderStatus {
    PLANNED,
    SUBMITTING,
    SUBMITTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED,
    EXPIRED,
    RECONCILED;

    public boolean terminal() {
        return switch (this) {
            case FILLED, CANCELLED, REJECTED, EXPIRED, RECONCILED -> true;
            case PLANNED, SUBMITTING, SUBMITTED, PARTIALLY_FILLED -> false;
        };
    }
}
