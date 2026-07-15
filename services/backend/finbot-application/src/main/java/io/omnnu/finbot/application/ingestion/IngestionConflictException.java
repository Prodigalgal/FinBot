package io.omnnu.finbot.application.ingestion;

public final class IngestionConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public IngestionConflictException(String message) {
        super(message);
    }
}
