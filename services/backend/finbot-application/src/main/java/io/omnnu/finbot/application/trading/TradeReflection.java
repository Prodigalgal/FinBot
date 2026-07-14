package io.omnnu.finbot.application.trading;

import java.util.List;
import java.util.Objects;

public record TradeReflection(
        boolean approved,
        List<String> reasons,
        TradeDecisionDraft decision) {
    public TradeReflection {
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        if (reasons.isEmpty()) {
            throw new IllegalArgumentException("Trade reflection requires reasons");
        }
        if (approved && decision == null) {
            throw new IllegalArgumentException("Approved reflection requires a decision");
        }
        if (!approved && decision != null) {
            throw new IllegalArgumentException("Rejected reflection must not contain a decision");
        }
    }
}
