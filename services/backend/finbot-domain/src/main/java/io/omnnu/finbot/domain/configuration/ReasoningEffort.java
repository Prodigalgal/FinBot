package io.omnnu.finbot.domain.configuration;

public enum ReasoningEffort {
    PROVIDER_DEFAULT,
    NONE,
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX;

    public boolean supports(ReasoningEffort requested) {
        if (requested == PROVIDER_DEFAULT) {
            return true;
        }
        return this != PROVIDER_DEFAULT && requested.ordinal() <= ordinal();
    }
}
