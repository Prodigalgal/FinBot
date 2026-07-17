package io.omnnu.finbot.application.configuration;

public record ProbeProviderCommand(
        String baseUrl,
        String apiKey,
        int requestTimeoutSeconds) {
}
