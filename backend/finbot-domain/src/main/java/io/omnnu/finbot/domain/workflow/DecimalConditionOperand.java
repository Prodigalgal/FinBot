package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.shared.DecimalValue;
import java.math.BigDecimal;

public record DecimalConditionOperand(BigDecimal value) implements ConditionOperand {
    public DecimalConditionOperand {
        value = DecimalValue.finite(value, "value");
    }
}
