package io.omnnu.finbot.domain.research;

public enum HypothesisStatus {
    PENDING_VALIDATION, WATCHING, CATALYST_NEAR, CONFIRMED, PAPER_VALIDATION, COMPLETED, REFUTED, EXPIRED;

    public boolean terminal() { return this == COMPLETED || this == REFUTED || this == EXPIRED; }
    public boolean allows(HypothesisStatus next) {
        if (terminal() || next == this || next == PENDING_VALIDATION) return false;
        if (next == REFUTED || next == EXPIRED) return true;
        return switch (this) {
            case PENDING_VALIDATION -> next == WATCHING;
            case WATCHING -> next == CATALYST_NEAR || next == CONFIRMED;
            case CATALYST_NEAR -> next == WATCHING || next == CONFIRMED;
            case CONFIRMED -> next == PAPER_VALIDATION || next == COMPLETED;
            case PAPER_VALIDATION -> next == COMPLETED;
            default -> false;
        };
    }
}
