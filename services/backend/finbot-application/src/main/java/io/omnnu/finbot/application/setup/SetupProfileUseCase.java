package io.omnnu.finbot.application.setup;

import java.util.List;

public interface SetupProfileUseCase {
    List<SetupProfileDefinition> profiles();

    SetupProfilePreview preview(SetupProfileId profileId);

    SetupProfileApplication apply(SetupProfileId profileId, String idempotencyKey);

    List<SetupProfileApplication> history(int limit);
}
