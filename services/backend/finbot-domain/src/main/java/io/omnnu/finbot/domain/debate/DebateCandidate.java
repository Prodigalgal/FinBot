package io.omnnu.finbot.domain.debate;

import io.omnnu.finbot.domain.consensus.AnonymousCandidateId;
import io.omnnu.finbot.domain.consensus.LogicalRoleKey;
import io.omnnu.finbot.domain.workflow.DebateId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import java.time.Instant;
import java.util.Objects;

public record DebateCandidate(
        DebateCandidateId candidateId,
        DebateId debateId,
        WorkflowNodeId originNodeId,
        LogicalRoleKey logicalRoleKey,
        AnonymousCandidateId anonymousCandidateId,
        DebateArtifactId proposalArtifactId,
        DebateArtifactId revisionArtifactId,
        Instant createdAt) {
    public DebateCandidate {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(debateId, "debateId");
        Objects.requireNonNull(originNodeId, "originNodeId");
        Objects.requireNonNull(logicalRoleKey, "logicalRoleKey");
        Objects.requireNonNull(anonymousCandidateId, "anonymousCandidateId");
        Objects.requireNonNull(proposalArtifactId, "proposalArtifactId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
