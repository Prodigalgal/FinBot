package io.omnnu.finbot.domain.market;

import io.omnnu.finbot.domain.shared.DecimalValue;
import java.math.BigDecimal;

public record Quantity(BigDecimal value) {
    public Quantity {
        value = DecimalValue.nonNegative(value, "quantity");
    }

    public static Quantity positive(BigDecimal value) {
        return new Quantity(DecimalValue.positive(value, "quantity"));
    }
}
