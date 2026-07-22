package io.omnnu.finbot.application.configuration.exception;

public final class ConfigurationConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConfigurationConflictException(String message) {
        super(message);
    }
}
