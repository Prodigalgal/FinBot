package io.omnnu.finbot.domain.workflow;

public sealed interface ConditionOperand permits
        TextConditionOperand,
        DecimalConditionOperand,
        BooleanConditionOperand,
        TextListConditionOperand {
}
