package io.omnnu.finbot.domain.oms;

import java.math.BigDecimal;
import java.util.Objects;

public record MatchingOutcome(
        boolean filled,
        BigDecimal filledPrice,
        BigDecimal filledQuantity,
        BigDecimal effectiveFeeRate,
        BigDecimal effectiveSlippageRate,
        BigDecimal feeUsdt,
        String matchReason) {

    public MatchingOutcome {
        matchReason = Objects.requireNonNull(matchReason, "matchReason");
        if (filled) {
            Objects.requireNonNull(filledPrice, "filledPrice");
            Objects.requireNonNull(filledQuantity, "filledQuantity");
            Objects.requireNonNull(effectiveFeeRate, "effectiveFeeRate");
            Objects.requireNonNull(effectiveSlippageRate, "effectiveSlippageRate");
            Objects.requireNonNull(feeUsdt, "feeUsdt");
        }
    }

    public static MatchingOutcome filled(
            BigDecimal filledPrice,
            BigDecimal filledQuantity,
            BigDecimal effectiveFeeRate,
            BigDecimal effectiveSlippageRate,
            BigDecimal feeUsdt,
            String matchReason) {
        return new MatchingOutcome(
                true,
                filledPrice,
                filledQuantity,
                effectiveFeeRate,
                effectiveSlippageRate,
                feeUsdt,
                matchReason);
    }

    public static MatchingOutcome unfilled(String matchReason) {
        return new MatchingOutcome(
                false,
                null,
                null,
                null,
                null,
                null,
                matchReason);
    }
}
