package io.omnnu.finbot.domain.trading;

public enum DirectionalAction implements DecisionAction {
    BUY,
    SELL;

    @Override
    public String code() {
        return name();
    }
}
