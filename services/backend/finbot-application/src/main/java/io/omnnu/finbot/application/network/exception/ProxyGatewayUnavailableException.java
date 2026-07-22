package io.omnnu.finbot.application.network.exception;

public final class ProxyGatewayUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ProxyGatewayUnavailableException(Throwable cause) {
        super("Proxy gateway operation failed", cause);
    }
}
