package io.omnnu.finbot.application.trading.exception;

public final class TradeAutomationConfigurationConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TradeAutomationConfigurationConflictException(String message) {
        super(message);
    }
}
