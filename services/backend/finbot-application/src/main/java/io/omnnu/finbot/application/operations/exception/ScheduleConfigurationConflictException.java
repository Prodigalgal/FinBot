package io.omnnu.finbot.application.operations.exception;

public final class ScheduleConfigurationConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ScheduleConfigurationConflictException(String message) {
        super(message);
    }
}
