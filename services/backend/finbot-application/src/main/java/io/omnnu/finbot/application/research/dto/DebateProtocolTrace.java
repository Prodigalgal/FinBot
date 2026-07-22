package io.omnnu.finbot.application.research.dto;

import io.omnnu.finbot.domain.consensus.BallotOrientation;
import io.omnnu.finbot.domain.consensus.ConsensusStatus;
import io.omnnu.finbot.domain.debate.DebateArtifactStatus;
import io.omnnu.finbot.domain.debate.DebatePhaseStatus;
import io.omnnu.finbot.domain.debate.DebatePhaseType;
import io.omnnu.finbot.domain.debate.DebateProtocol;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Anonymous, read-only projection of one persisted SDB-SCA protocol run. */
public record DebateProtocolTrace(
        String debateId,
        DebateProtocol protocol,
        List<Phase> phases,
        List<AnonymousArtifact> artifacts,
        List<AnonymousBallot> ballots,
        Decision decision) {
    public DebateProtocolTrace {
        debateId = required(debateId, "debateId");
        Objects.requireNonNull(protocol, "protocol");
        phases = List.copyOf(Objects.requireNonNull(phases, "phases"));
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        ballots = List.copyOf(Objects.requireNonNull(ballots, "ballots"));
    }

    public record Phase(
            DebatePhaseType phaseType,
            DebatePhaseStatus status,
            int requiredTasks,
            int terminalTasks,
            int pendingTasks,
            int claimedTasks,
            int completedTasks,
            int failedTasks,
            int timedOutTasks,
            int cancelledTasks,
            Instant deadline,
            Instant openedAt,
            Instant revealedAt,
            Instant completedAt,
            boolean recoveryPoint) {
        public Phase {
            Objects.requireNonNull(phaseType, "phaseType");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(deadline, "deadline");
            if (requiredTasks < 1 || terminalTasks < 0 || terminalTasks > requiredTasks) {
                throw new IllegalArgumentException("Invalid debate phase progress");
            }
        }
    }

    public record AnonymousArtifact(
            String artifactId,
            DebatePhaseType phaseType,
            String candidateAlias,
            String targetCandidateAlias,
            DebateArtifactStatus status,
            String contentHash,
            String contentJson,
            Instant sealedAt,
            Instant revealedAt) {
        public AnonymousArtifact {
            artifactId = required(artifactId, "artifactId");
            Objects.requireNonNull(phaseType, "phaseType");
            Objects.requireNonNull(status, "status");
            contentHash = required(contentHash, "contentHash");
            contentJson = required(contentJson, "contentJson");
            Objects.requireNonNull(sealedAt, "sealedAt");
        }
    }

    public record AnonymousBallot(
            BallotOrientation orientation,
            String preferenceTiersJson,
            String contentHash,
            Instant createdAt) {
        public AnonymousBallot {
            Objects.requireNonNull(orientation, "orientation");
            preferenceTiersJson = required(preferenceTiersJson, "preferenceTiersJson");
            contentHash = required(contentHash, "contentHash");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record Decision(
            ConsensusStatus status,
            String winnerCandidateAlias,
            int contributingRoleCount,
            String undefeatedCandidatesJson,
            String pairwiseMatrixJson,
            String strongestPathsJson,
            String rankingJson,
            String forecastJson,
            String explanation,
            String decisionHash,
            Instant decidedAt) {
        public Decision {
            Objects.requireNonNull(status, "status");
            undefeatedCandidatesJson = required(undefeatedCandidatesJson, "undefeatedCandidatesJson");
            pairwiseMatrixJson = required(pairwiseMatrixJson, "pairwiseMatrixJson");
            strongestPathsJson = required(strongestPathsJson, "strongestPathsJson");
            rankingJson = required(rankingJson, "rankingJson");
            decisionHash = required(decisionHash, "decisionHash");
            Objects.requireNonNull(decidedAt, "decidedAt");
            if (contributingRoleCount < 0) {
                throw new IllegalArgumentException("contributingRoleCount must not be negative");
            }
        }
    }

    private static String required(String value, String fieldName) {
        var normalized = Objects.requireNonNull(value, fieldName).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
