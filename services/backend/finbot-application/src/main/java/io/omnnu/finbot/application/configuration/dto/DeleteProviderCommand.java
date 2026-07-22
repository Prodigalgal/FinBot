package io.omnnu.finbot.application.configuration.dto;

public record DeleteProviderCommand(String profileId, long expectedVersion) {
}
