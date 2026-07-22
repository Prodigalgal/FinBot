package io.omnnu.finbot.application.research.service;

import io.omnnu.finbot.application.research.dto.ResearchCaseView;
import io.omnnu.finbot.application.research.port.out.ResearchSegmentationStore;

import io.omnnu.finbot.domain.research.ResearchArtifactId;
import io.omnnu.finbot.domain.research.ResearchCaseId;
import io.omnnu.finbot.domain.research.ResearchSegmentId;
import io.omnnu.finbot.domain.research.ResearchSegmentStatus;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

public final class ResearchSegmentationService {
    private final ResearchSegmentationStore store;

    public ResearchSegmentationService(ResearchSegmentationStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public ResearchCaseId ensureLiveCase(
            WorkflowRunId liveRunId,
            WorkflowTrigger trigger,
            String requestSummary,
            Instant startedAt) {
        var caseId = new ResearchCaseId("case_" + hash(liveRunId.value()).substring(0, 40));
        store.ensureLiveCase(
                caseId,
                segmentId(caseId, "evidence"),
                segmentId(caseId, "live"),
                liveRunId,
                trigger,
                requestSummary,
                startedAt);
        return caseId;
    }

    public void recordEvidenceSnapshot(
            WorkflowRunId liveRunId,
            ResearchArtifactId artifactId,
            Instant completedAt) {
        store.recordEvidenceSnapshot(liveRunId, artifactId, completedAt);
    }

    public void skipEvidence(WorkflowRunId liveRunId, Instant changedAt) {
        store.transitionEvidence(
                liveRunId,
                ResearchSegmentStatus.SKIPPED,
                null,
                null,
                changedAt);
    }

    public void failEvidence(
            WorkflowRunId liveRunId,
            String errorCode,
            String errorMessage,
            Instant changedAt) {
        store.transitionEvidence(
                liveRunId,
                ResearchSegmentStatus.FAILED,
                errorCode,
                errorMessage,
                changedAt);
    }

    public void registerDemoBranch(
            WorkflowRunId liveRunId,
            WorkflowRunId demoRunId,
            Instant startedAt) {
        var caseView = store.findByRunId(liveRunId)
                .orElseThrow(() -> new IllegalStateException("Live research case is missing"));
        store.registerDemoBranch(
                liveRunId,
                segmentId(caseView.caseId(), "demo"),
                demoRunId,
                startedAt);
    }

    public void transition(
            WorkflowRunId workflowRunId,
            ResearchSegmentStatus status,
            String errorCode,
            String errorMessage,
            Instant changedAt) {
        store.transition(workflowRunId, status, errorCode, errorMessage, changedAt);
    }

    public Optional<ResearchCaseView> findByRunId(WorkflowRunId workflowRunId) {
        return store.findByRunId(workflowRunId);
    }

    private static ResearchSegmentId segmentId(ResearchCaseId caseId, String suffix) {
        return new ResearchSegmentId("segment_" + hash(caseId.value() + ':' + suffix).substring(0, 40));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
