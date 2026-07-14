package io.omnnu.finbot.application.ai;

public final class AiBudgetExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AiBudgetExceededException(String message) {
        super(message);
    }
}
