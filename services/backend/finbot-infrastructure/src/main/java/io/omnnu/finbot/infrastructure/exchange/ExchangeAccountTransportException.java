package io.omnnu.finbot.infrastructure.exchange;

public final class ExchangeAccountTransportException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final int attempts;

    ExchangeAccountTransportException(
            String errorCode,
            String message,
            int attempts,
            Throwable failure) {
        super(message);
        this.errorCode = errorCode;
        this.attempts = attempts;
        addSuppressed(failure);
    }

    public String errorCode() {
        return errorCode;
    }

    public int attempts() {
        return attempts;
    }
}
