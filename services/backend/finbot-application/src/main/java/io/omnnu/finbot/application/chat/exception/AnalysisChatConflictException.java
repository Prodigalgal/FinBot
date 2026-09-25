package io.omnnu.finbot.application.chat.exception;

public final class AnalysisChatConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AnalysisChatConflictException(String message) {
        super(message);
    }
}
