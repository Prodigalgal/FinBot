package io.omnnu.finbot.domain.trading;

public enum NonDirectionalAction implements DecisionAction {
    HOLD,
    WATCH;

    @Override
    public String code() {
        return name();
    }
}
