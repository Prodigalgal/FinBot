package io.omnnu.finbot.domain.risk;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record RiskAssessmentPlan(
        RiskAssessmentStatus status,
        List<String> reasons,
        BigDecimal quantity,
        BigDecimal notionalUsdt,
        BigDecimal leverage,
        BigDecimal initialMarginUsdt,
        BigDecimal estimatedMaximumLossUsdt,
        BigDecimal approximateLiquidationPrice) {

    public RiskAssessmentPlan {
        Objects.requireNonNull(status, "status");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        if (status == RiskAssessmentStatus.APPROVED) {
            requirePositive(quantity, "quantity");
            requirePositive(notionalUsdt, "notionalUsdt");
            requirePositive(leverage, "leverage");
            requirePositive(initialMarginUsdt, "initialMarginUsdt");
            requirePositive(estimatedMaximumLossUsdt, "estimatedMaximumLossUsdt");
            requirePositive(approximateLiquidationPrice, "approximateLiquidationPrice");
        } else if (quantity != null || notionalUsdt != null || leverage != null
                || initialMarginUsdt != null || estimatedMaximumLossUsdt != null
                || approximateLiquidationPrice != null) {
            throw new IllegalArgumentException("Blocked risk plan must not contain execution values");
        }
    }

    public static RiskAssessmentPlan blocked(List<String> reasons) {
        if (reasons.isEmpty()) {
            throw new IllegalArgumentException("Blocked risk plan requires at least one reason");
        }
        return new RiskAssessmentPlan(
                RiskAssessmentStatus.BLOCKED,
                reasons,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive for an approved risk plan");
        }
    }
}
