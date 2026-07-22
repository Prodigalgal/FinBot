package io.omnnu.finbot.application.market.exception;

import java.util.Objects;

public final class MarketDataFetchException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String errorCode;

    public MarketDataFetchException(String errorCode, String safeMessage) {
        super(Objects.requireNonNull(safeMessage, "safeMessage"));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public String errorCode() {
        return errorCode;
    }
}
