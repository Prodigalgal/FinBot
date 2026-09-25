package io.omnnu.finbot.application.chat.exception;

public final class AnalysisChatNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AnalysisChatNotFoundException(String chatId) {
        super("Analysis chat not found: " + chatId);
    }
}
