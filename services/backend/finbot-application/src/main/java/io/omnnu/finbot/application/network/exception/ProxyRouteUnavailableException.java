package io.omnnu.finbot.application.network.exception;

public final class ProxyRouteUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ProxyRouteUnavailableException(String message) {
        super(message);
    }
}
