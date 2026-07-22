package io.omnnu.finbot.application.workflow.exception;

public final class DebateProtocolConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DebateProtocolConflictException(String message) {
        super(message);
    }

    public DebateProtocolConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
