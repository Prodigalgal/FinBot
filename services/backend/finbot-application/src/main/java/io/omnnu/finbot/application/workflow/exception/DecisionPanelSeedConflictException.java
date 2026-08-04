package io.omnnu.finbot.application.workflow.exception;

public final class DecisionPanelSeedConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DecisionPanelSeedConflictException(String message) {
        super(message);
    }
}
