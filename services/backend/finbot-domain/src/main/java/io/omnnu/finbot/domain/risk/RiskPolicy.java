package io.omnnu.finbot.domain.risk;

import io.omnnu.finbot.domain.shared.DecimalValue;
import io.omnnu.finbot.domain.shared.DomainText;
import java.math.BigDecimal;

public record RiskPolicy(
        String version,
        boolean testEnvironmentOnly,
        BigDecimal minimumConfidence,
        BigDecimal riskBudgetUsdt,
        BigDecimal maximumNotionalUsdt,
        BigDecimal preferredLeverage,
        BigDecimal maximumLeverage,
        int maximumOpenPositions,
        BigDecimal maximumStopDistance,
        BigDecimal takerFeeRate,
        BigDecimal slippageRate,
        BigDecimal liquidationBufferRate) {

    public RiskPolicy {
        version = DomainText.required(version, "risk policy version", 80);
        minimumConfidence = ratio(minimumConfidence, "minimumConfidence", true);
        riskBudgetUsdt = DecimalValue.positive(riskBudgetUsdt, "riskBudgetUsdt");
        maximumNotionalUsdt = DecimalValue.positive(maximumNotionalUsdt, "maximumNotionalUsdt");
        preferredLeverage = DecimalValue.positive(preferredLeverage, "preferredLeverage");
        maximumLeverage = DecimalValue.positive(maximumLeverage, "maximumLeverage");
        maximumStopDistance = ratio(maximumStopDistance, "maximumStopDistance", false);
        takerFeeRate = ratio(takerFeeRate, "takerFeeRate", true);
        slippageRate = ratio(slippageRate, "slippageRate", true);
        liquidationBufferRate = ratio(liquidationBufferRate, "liquidationBufferRate", true);
        if (preferredLeverage.compareTo(BigDecimal.ONE) < 0
                || maximumLeverage.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("Leverage values must be at least 1");
        }
        if (preferredLeverage.compareTo(maximumLeverage) > 0) {
            throw new IllegalArgumentException("preferredLeverage must not exceed maximumLeverage");
        }
        if (maximumOpenPositions < 1 || maximumOpenPositions > 100) {
            throw new IllegalArgumentException("maximumOpenPositions must be between 1 and 100");
        }
    }

    private static BigDecimal ratio(BigDecimal value, String name, boolean allowZero) {
        var normalized = DecimalValue.nonNegative(value, name);
        if ((!allowZero && normalized.signum() == 0) || normalized.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException(name + " must be within its ratio range");
        }
        return normalized;
    }
}
