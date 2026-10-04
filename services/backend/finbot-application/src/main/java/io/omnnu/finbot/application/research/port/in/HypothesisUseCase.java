package io.omnnu.finbot.application.research.port.in;

import io.omnnu.finbot.application.research.dto.HypothesisView;
import io.omnnu.finbot.application.research.dto.HypothesisRevision;
import io.omnnu.finbot.domain.research.HypothesisStatus;
import java.util.List;

public interface HypothesisUseCase {
    int captureAndExpire(int limit);
    List<HypothesisView> recent(int limit);
    List<HypothesisRevision> history(String hypothesisId);
    HypothesisView transition(String hypothesisId, long expectedVersion, HypothesisStatus status, String reason);
}
