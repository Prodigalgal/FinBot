package io.omnnu.finbot.application.catalog.exception;

public final class CatalogConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CatalogConflictException(String message) {
        super(message);
    }
}
