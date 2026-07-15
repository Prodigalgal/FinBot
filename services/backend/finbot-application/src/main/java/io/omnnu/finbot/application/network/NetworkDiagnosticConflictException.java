package io.omnnu.finbot.application.network;

public final class NetworkDiagnosticConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NetworkDiagnosticConflictException(String message) {
        super(message);
    }
}
