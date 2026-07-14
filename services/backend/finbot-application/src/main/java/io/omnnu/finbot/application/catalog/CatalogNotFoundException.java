package io.omnnu.finbot.application.catalog;

public final class CatalogNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CatalogNotFoundException(String message) {
        super(message);
    }
}
