package io.omnnu.finbot.application.configuration;

public record UpdateSettingCommand(String key, String value, long expectedVersion) {
}
