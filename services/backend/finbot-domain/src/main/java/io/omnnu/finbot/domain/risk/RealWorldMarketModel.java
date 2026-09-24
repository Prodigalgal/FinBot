package io.omnnu.finbot.domain.risk;

import io.omnnu.finbot.domain.shared.DecimalValue;
import java.math.BigDecimal;
import java.util.Objects;

public record RealWorldMarketModel(
        BigDecimal referenceDepthUsdt,
        BigDecimal fundingRate,
        int fundingPeriods,
        BigDecimal makerFeeRate,
        BigDecimal maxEffectiveSlippageRate) {

    public RealWorldMarketModel {
        referenceDepthUsdt = DecimalValue.positive(referenceDepthUsdt, "referenceDepthUsdt");
        fundingRate = Objects.requireNonNull(fundingRate, "fundingRate");
        if (fundingPeriods < 0) {
            throw new IllegalArgumentException("fundingPeriods must not be negative");
        }
        makerFeeRate = DecimalValue.nonNegative(makerFeeRate, "makerFeeRate");
        maxEffectiveSlippageRate = DecimalValue.positive(maxEffectiveSlippageRate, "maxEffectiveSlippageRate");
    }

    public boolean isLinear() {
        return fundingPeriods == 0 && fundingRate.signum() == 0;
    }

    public static RealWorldMarketModel linear() {
        return new RealWorldMarketModel(
                new BigDecimal("1000000000"),
                BigDecimal.ZERO,
                0,
                new BigDecimal("0.0002"),
                new BigDecimal("0.05"));
    }

    public static RealWorldMarketModel standardPerpetual() {
        return new RealWorldMarketModel(
                new BigDecimal("500000"),
                new BigDecimal("0.0001"),
                1,
                new BigDecimal("0.0002"),
                new BigDecimal("0.02"));
    }

    public static RealWorldMarketModel of(
            BigDecimal referenceDepthUsdt,
            BigDecimal fundingRate,
            int fundingPeriods,
            BigDecimal makerFeeRate,
            BigDecimal maxEffectiveSlippageRate) {
        return new RealWorldMarketModel(
                referenceDepthUsdt,
                fundingRate,
                fundingPeriods,
                makerFeeRate,
                maxEffectiveSlippageRate);
    }
}
