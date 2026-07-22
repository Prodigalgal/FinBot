package io.omnnu.finbot.application.setup.port.in;

import io.omnnu.finbot.application.setup.dto.SetupProfileApplication;
import io.omnnu.finbot.application.setup.dto.SetupProfileDefinition;
import io.omnnu.finbot.application.setup.dto.SetupProfileId;
import io.omnnu.finbot.application.setup.dto.SetupProfilePreview;

import java.util.List;

public interface SetupProfileUseCase {
    List<SetupProfileDefinition> profiles();

    SetupProfilePreview preview(SetupProfileId profileId);

    SetupProfileApplication apply(SetupProfileId profileId, String idempotencyKey);

    List<SetupProfileApplication> history(int limit);
}
