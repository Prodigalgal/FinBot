package io.omnnu.finbot.infrastructure.quant;

public final class QuantResearchStreamException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public QuantResearchStreamException(String message) {
        super(message);
    }

    public QuantResearchStreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
