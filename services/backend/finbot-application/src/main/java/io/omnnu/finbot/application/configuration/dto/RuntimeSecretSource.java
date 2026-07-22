package io.omnnu.finbot.application.configuration.dto;

public enum RuntimeSecretSource {
    DATABASE_OVERRIDE,
    ENVIRONMENT_FALLBACK,
    UNCONFIGURED
}
