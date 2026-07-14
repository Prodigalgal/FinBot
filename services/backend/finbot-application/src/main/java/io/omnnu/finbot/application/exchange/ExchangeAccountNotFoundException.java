package io.omnnu.finbot.application.exchange;

public final class ExchangeAccountNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ExchangeAccountNotFoundException(String message) {
        super(message);
    }
}
