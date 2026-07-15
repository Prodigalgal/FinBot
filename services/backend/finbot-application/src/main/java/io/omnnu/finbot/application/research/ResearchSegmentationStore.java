package io.omnnu.finbot.application.research;

import io.omnnu.finbot.domain.research.ResearchArtifactId;
import io.omnnu.finbot.domain.research.ResearchCaseId;
import io.omnnu.finbot.domain.research.ResearchSegmentId;
import io.omnnu.finbot.domain.research.ResearchSegmentStatus;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import java.time.Instant;
import java.util.Optional;

public interface ResearchSegmentationStore {
    void ensureLiveCase(
            ResearchCaseId caseId,
            ResearchSegmentId evidenceSegmentId,
            ResearchSegmentId liveSegmentId,
            WorkflowRunId liveRunId,
            WorkflowTrigger trigger,
            String requestSummary,
            Instant startedAt);

    void recordEvidenceSnapshot(
            WorkflowRunId liveRunId,
            ResearchArtifactId artifactId,
            Instant completedAt);

    void transitionEvidence(
            WorkflowRunId liveRunId,
            ResearchSegmentStatus status,
            String errorCode,
            String errorMessage,
            Instant changedAt);

    void registerDemoBranch(
            WorkflowRunId liveRunId,
            ResearchSegmentId demoSegmentId,
            WorkflowRunId demoRunId,
            Instant startedAt);

    void transition(
            WorkflowRunId workflowRunId,
            ResearchSegmentStatus status,
            String errorCode,
            String errorMessage,
            Instant changedAt);

    Optional<ResearchCaseView> findByRunId(WorkflowRunId workflowRunId);
}
