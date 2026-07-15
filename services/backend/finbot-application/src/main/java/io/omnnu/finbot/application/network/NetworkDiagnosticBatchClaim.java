package io.omnnu.finbot.application.network;

public record NetworkDiagnosticBatchClaim(
        String requestFingerprint,
        boolean created) {
}
