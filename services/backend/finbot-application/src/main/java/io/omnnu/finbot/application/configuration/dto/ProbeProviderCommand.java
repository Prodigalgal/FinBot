package io.omnnu.finbot.application.configuration.dto;

public record ProbeProviderCommand(
        String baseUrl,
        String apiKey,
        int requestTimeoutSeconds) {
}
