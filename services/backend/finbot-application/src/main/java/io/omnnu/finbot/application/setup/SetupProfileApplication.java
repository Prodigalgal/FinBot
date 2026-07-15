package io.omnnu.finbot.application.setup;

import java.time.Instant;
import java.util.List;

public record SetupProfileApplication(
        String applicationId,
        SetupProfileId profileId,
        List<String> appliedKeys,
        List<String> preservedKeys,
        List<String> skippedKeys,
        Instant appliedAt) {
    public SetupProfileApplication {
        appliedKeys = List.copyOf(appliedKeys);
        preservedKeys = List.copyOf(preservedKeys);
        skippedKeys = List.copyOf(skippedKeys);
    }
}
