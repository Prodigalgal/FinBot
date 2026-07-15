package io.omnnu.finbot.application.network;

public record NetworkDiagnosticStart(
        NetworkDiagnostic diagnostic,
        boolean created) {
}
