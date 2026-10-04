package io.omnnu.finbot.application.paper.exception;

public final class LocalPaperNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public LocalPaperNotFoundException(String message) { super(message); }
}
