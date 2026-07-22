package io.omnnu.finbot.application.setup.port.out;

import io.omnnu.finbot.application.setup.dto.SetupProfileApplication;
import io.omnnu.finbot.application.setup.dto.SetupProfileId;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface SetupProfileRepository {
    SetupProfileApplication apply(
            String applicationId,
            String idempotencyKey,
            SetupProfileId profileId,
            Map<String, String> values,
            Instant appliedAt);

    List<SetupProfileApplication> history(int limit);
}
