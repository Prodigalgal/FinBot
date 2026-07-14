package io.omnnu.finbot.domain.trading;

import io.omnnu.finbot.domain.shared.DecimalValue;
import java.math.BigDecimal;

public record Confidence(BigDecimal value) {
    private static final BigDecimal ONE = BigDecimal.ONE;

    public Confidence {
        value = DecimalValue.nonNegative(value, "confidence");
        if (value.compareTo(ONE) > 0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }
}
