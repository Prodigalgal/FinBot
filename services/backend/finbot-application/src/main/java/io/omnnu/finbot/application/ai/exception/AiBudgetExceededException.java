package io.omnnu.finbot.application.ai.exception;

public final class AiBudgetExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AiBudgetExceededException(String message) {
        super(message);
    }
}
