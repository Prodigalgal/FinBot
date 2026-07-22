package io.omnnu.finbot.application.ai.exception;

public class AiProviderUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AiProviderUnavailableException(String message) {
        super(message);
    }
}
