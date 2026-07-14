package io.omnnu.finbot.domain.workflow;

import java.util.Objects;
import java.util.regex.Pattern;

public record WorkflowCondition(
        String field,
        WorkflowConditionOperator operator,
        ConditionOperand operand) {
    private static final Pattern FIELD = Pattern.compile("[a-zA-Z][a-zA-Z0-9_.-]{0,255}");

    public WorkflowCondition {
        field = Objects.requireNonNull(field, "field").strip();
        Objects.requireNonNull(operator, "operator");
        if (!FIELD.matcher(field).matches()) {
            throw new IllegalArgumentException("Invalid workflow condition field");
        }
        if (operator.requiresOperand() != (operand != null)) {
            throw new IllegalArgumentException("Workflow condition operand does not match operator");
        }
    }
}
