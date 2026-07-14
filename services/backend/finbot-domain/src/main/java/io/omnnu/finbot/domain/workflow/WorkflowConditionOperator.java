package io.omnnu.finbot.domain.workflow;

public enum WorkflowConditionOperator {
    EXISTS,
    EQ,
    NE,
    IN,
    NOT_IN,
    GT,
    GTE,
    LT,
    LTE,
    CONTAINS,
    TRUTHY,
    FALSY;

    public boolean requiresOperand() {
        return switch (this) {
            case EXISTS, TRUTHY, FALSY -> false;
            case EQ, NE, IN, NOT_IN, GT, GTE, LT, LTE, CONTAINS -> true;
        };
    }
}
