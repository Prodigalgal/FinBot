package io.omnnu.finbot.domain.workflow;

import java.util.List;

public record TextListConditionOperand(List<String> values) implements ConditionOperand {
    public TextListConditionOperand {
        values = List.copyOf(values);
        if (values.isEmpty() || values.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Condition operand values must not be empty or blank");
        }
    }
}
