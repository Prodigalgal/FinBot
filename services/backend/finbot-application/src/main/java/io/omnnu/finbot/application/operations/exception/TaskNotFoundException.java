package io.omnnu.finbot.application.operations.exception;

public final class TaskNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TaskNotFoundException(String message) {
        super(message);
    }
}
