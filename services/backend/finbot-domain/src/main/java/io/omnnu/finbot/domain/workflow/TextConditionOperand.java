package io.omnnu.finbot.domain.workflow;

import java.util.Objects;

public record TextConditionOperand(String value) implements ConditionOperand {
    public TextConditionOperand {
        value = Objects.requireNonNull(value, "value");
    }
}
