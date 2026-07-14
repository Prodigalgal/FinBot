package io.omnnu.finbot.application.ingestion;

import java.util.Objects;

public final class SourceCollectionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final boolean blocked;

    public SourceCollectionException(String errorCode, String safeMessage, boolean blocked) {
        super(Objects.requireNonNull(safeMessage, "safeMessage"));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.blocked = blocked;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean blocked() {
        return blocked;
    }
}
