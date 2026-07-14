package io.omnnu.finbot.domain.trading;

public sealed interface DecisionAction permits DirectionalAction, NonDirectionalAction {
    String code();
}
