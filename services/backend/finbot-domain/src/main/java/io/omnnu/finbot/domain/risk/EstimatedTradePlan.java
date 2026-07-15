package io.omnnu.finbot.domain.risk;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record EstimatedTradePlan(
        EstimatedTradePlanStatus status,
        List<String> reasons,
        BigDecimal quantity,
        BigDecimal notionalUsdt,
        BigDecimal leverage,
        BigDecimal initialMarginUsdt,
        BigDecimal estimatedEntryCostUsdt,
        BigDecimal estimatedTargetExitCostUsdt,
        BigDecimal estimatedStopExitCostUsdt,
        BigDecimal estimatedProfitUsdt,
        BigDecimal estimatedLossUsdt,
        BigDecimal riskRewardRatio) {

    public EstimatedTradePlan {
        Objects.requireNonNull(status, "status");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        if (status == EstimatedTradePlanStatus.ESTIMATED) {
            requirePositive(quantity, "quantity");
            requirePositive(notionalUsdt, "notionalUsdt");
            requirePositive(leverage, "leverage");
            requirePositive(initialMarginUsdt, "initialMarginUsdt");
            requireNonNegative(estimatedEntryCostUsdt, "estimatedEntryCostUsdt");
            requireNonNegative(estimatedTargetExitCostUsdt, "estimatedTargetExitCostUsdt");
            requireNonNegative(estimatedStopExitCostUsdt, "estimatedStopExitCostUsdt");
            requirePositive(estimatedProfitUsdt, "estimatedProfitUsdt");
            requirePositive(estimatedLossUsdt, "estimatedLossUsdt");
            requirePositive(riskRewardRatio, "riskRewardRatio");
        } else if (quantity != null || notionalUsdt != null || leverage != null
                || initialMarginUsdt != null || estimatedEntryCostUsdt != null
                || estimatedTargetExitCostUsdt != null || estimatedStopExitCostUsdt != null
                || estimatedProfitUsdt != null || estimatedLossUsdt != null
                || riskRewardRatio != null) {
            throw new IllegalArgumentException("Blocked estimate must not contain projection values");
        }
    }

    public static EstimatedTradePlan blocked(List<String> reasons) {
        if (reasons.isEmpty()) {
            throw new IllegalArgumentException("Blocked estimate requires at least one reason");
        }
        return new EstimatedTradePlan(
                EstimatedTradePlanStatus.BLOCKED,
                reasons,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive for an estimated trade");
        }
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative for an estimated trade");
        }
    }
}
