package io.omnnu.finbot.application.research.dto;

import io.omnnu.finbot.domain.research.ResearchArtifactId;
import io.omnnu.finbot.domain.research.ResearchCaseId;
import io.omnnu.finbot.domain.research.ResearchCaseStatus;
import java.time.Instant;
import java.util.List;

public record ResearchCaseView(
        ResearchCaseId caseId,
        ResearchCaseStatus status,
        String requestSummary,
        ResearchArtifactId evidenceArtifactId,
        List<ResearchSegmentView> segments,
        Instant createdAt,
        Instant completedAt,
        Instant updatedAt) {
    public ResearchCaseView {
        segments = List.copyOf(segments);
    }
}
