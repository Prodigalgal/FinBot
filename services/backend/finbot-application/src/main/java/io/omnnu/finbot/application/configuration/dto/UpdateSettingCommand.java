package io.omnnu.finbot.application.configuration.dto;

public record UpdateSettingCommand(String key, String value, long expectedVersion) {
}
