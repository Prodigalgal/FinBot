package io.omnnu.finbot.application.research;

import io.omnnu.finbot.domain.research.ResearchDataPlane;
import io.omnnu.finbot.domain.research.ResearchArtifactId;
import io.omnnu.finbot.domain.research.ResearchSegmentId;
import io.omnnu.finbot.domain.research.ResearchSegmentStatus;
import io.omnnu.finbot.domain.research.ResearchSegmentType;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;

public record ResearchSegmentView(
        ResearchSegmentId segmentId,
        ResearchSegmentType segmentType,
        ResearchDataPlane dataPlane,
        WorkflowRunId workflowRunId,
        ResearchArtifactId evidenceArtifactId,
        ResearchSegmentStatus status,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant completedAt) {
}
