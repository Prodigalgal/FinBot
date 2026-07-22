package io.omnnu.finbot.application.network.dto;

public record NetworkDiagnosticStart(
        NetworkDiagnostic diagnostic,
        boolean created) {
}
