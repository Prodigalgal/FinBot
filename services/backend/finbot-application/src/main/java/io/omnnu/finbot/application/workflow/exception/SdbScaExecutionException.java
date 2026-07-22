package io.omnnu.finbot.application.workflow.exception;

import java.util.Objects;

public final class SdbScaExecutionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final boolean retryable;

    public SdbScaExecutionException(String errorCode, String message, boolean retryable) {
        super(Objects.requireNonNull(message, "message"));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.retryable = retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
