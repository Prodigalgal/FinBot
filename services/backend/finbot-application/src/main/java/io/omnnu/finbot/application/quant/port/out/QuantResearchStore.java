package io.omnnu.finbot.application.quant.port.out;

import io.omnnu.finbot.domain.quant.QuantResearchEvent;
import io.omnnu.finbot.domain.quant.QuantResearchRequest;
import io.omnnu.finbot.domain.quant.ResearchCompletedEvent;
import io.omnnu.finbot.domain.quant.ResearchFailedEvent;
import io.omnnu.finbot.domain.quant.ResearchErrorCode;
import io.omnnu.finbot.domain.quant.ResearchRunId;
import io.omnnu.finbot.domain.research.ResearchArtifactId;
import java.time.Instant;

public interface QuantResearchStore {
    void start(QuantResearchRequest request, ResearchArtifactId inputArtifactId);

    void appendEvent(QuantResearchEvent event);

    void complete(
            ResearchCompletedEvent completed,
            ResearchArtifactId resultArtifactId,
            Instant completedAt);

    void fail(ResearchFailedEvent failed, Instant failedAt);

    void failTransport(
            ResearchRunId researchRunId,
            ResearchErrorCode errorCode,
            String safeMessage,
            Instant failedAt);
}
