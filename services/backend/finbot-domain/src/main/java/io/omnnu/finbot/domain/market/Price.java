package io.omnnu.finbot.domain.market;

import io.omnnu.finbot.domain.shared.DecimalValue;
import java.math.BigDecimal;

public record Price(BigDecimal value) {
    public Price {
        value = DecimalValue.positive(value, "price");
    }
}
