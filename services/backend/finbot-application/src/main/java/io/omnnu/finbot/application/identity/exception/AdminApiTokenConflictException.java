package io.omnnu.finbot.application.identity.exception;

public final class AdminApiTokenConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AdminApiTokenConflictException(String message) {
        super(message);
    }
}
