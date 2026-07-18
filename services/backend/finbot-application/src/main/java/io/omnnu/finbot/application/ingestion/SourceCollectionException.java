package io.omnnu.finbot.application.ingestion;

import java.util.Objects;

public final class SourceCollectionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final boolean blocked;
    private final Integer statusCode;

    public SourceCollectionException(String errorCode, String safeMessage, boolean blocked) {
        this(errorCode, safeMessage, blocked, null);
    }

    public SourceCollectionException(
            String errorCode,
            String safeMessage,
            boolean blocked,
            Integer statusCode) {
        super(Objects.requireNonNull(safeMessage, "safeMessage"));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.blocked = blocked;
        if (statusCode != null && (statusCode < 100 || statusCode > 599)) {
            throw new IllegalArgumentException("statusCode is invalid");
        }
        this.statusCode = statusCode;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean blocked() {
        return blocked;
    }

    public Integer statusCode() {
        return statusCode;
    }
}
