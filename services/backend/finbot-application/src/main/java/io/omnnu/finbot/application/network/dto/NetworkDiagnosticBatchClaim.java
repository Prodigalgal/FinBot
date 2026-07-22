package io.omnnu.finbot.application.network.dto;

public record NetworkDiagnosticBatchClaim(
        String requestFingerprint,
        boolean created) {
}
