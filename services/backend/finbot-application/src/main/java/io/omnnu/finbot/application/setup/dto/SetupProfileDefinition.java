package io.omnnu.finbot.application.setup.dto;

import java.util.Map;

public record SetupProfileDefinition(
        SetupProfileId profileId,
        String displayName,
        String description,
        Map<String, String> values) {
    public SetupProfileDefinition {
        values = Map.copyOf(values);
    }
}
