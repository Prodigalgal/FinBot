package io.omnnu.finbot.application.setup;

import java.util.List;

public record SetupProfilePreview(
        SetupProfileDefinition profile,
        List<ValueChange> changes,
        List<String> preservedKeys,
        List<String> missingKeys) {
    public SetupProfilePreview {
        changes = List.copyOf(changes);
        preservedKeys = List.copyOf(preservedKeys);
        missingKeys = List.copyOf(missingKeys);
    }

    public record ValueChange(String key, String currentValue, String proposedValue) {
    }
}
