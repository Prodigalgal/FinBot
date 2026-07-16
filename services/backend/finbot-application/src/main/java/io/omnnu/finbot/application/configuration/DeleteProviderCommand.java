package io.omnnu.finbot.application.configuration;

public record DeleteProviderCommand(String profileId, long expectedVersion) {
}
