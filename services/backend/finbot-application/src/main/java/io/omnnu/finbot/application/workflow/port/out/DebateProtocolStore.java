package io.omnnu.finbot.application.workflow.port.out;

import io.omnnu.finbot.domain.consensus.ConsensusBallot;
import io.omnnu.finbot.domain.consensus.ConsensusDecision;
import io.omnnu.finbot.domain.debate.DebateArtifact;
import io.omnnu.finbot.domain.debate.DebateArtifactId;
import io.omnnu.finbot.domain.debate.DebateCandidate;
import io.omnnu.finbot.domain.debate.DebateCandidateId;
import io.omnnu.finbot.domain.debate.DebatePhase;
import io.omnnu.finbot.domain.debate.DebatePhaseId;
import io.omnnu.finbot.domain.debate.DebateTask;
import io.omnnu.finbot.domain.debate.DebateTaskId;
import io.omnnu.finbot.domain.workflow.DebateId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DebateProtocolStore {
    void createPhase(DebatePhase phase, List<DebateTask> tasks);

    Optional<DebatePhase> phase(DebatePhaseId phaseId);

    Optional<DebatePhase> currentPhase(DebateId debateId);

    List<DebateTask> claimTasks(
            DebatePhaseId phaseId,
            String leaseOwner,
            int maximumTasks,
            Duration leaseDuration,
            Instant now);

    void sealArtifact(DebateArtifact artifact, String leaseOwner, Instant completedAt);

    void failTask(
            DebateTaskId taskId,
            String leaseOwner,
            String errorCode,
            String errorMessage,
            Instant completedAt);

    void timeoutTask(DebateTaskId taskId, Instant completedAt);

    boolean revealPhase(DebatePhaseId phaseId, long expectedVersion, Instant revealedAt);

    List<DebateTask> tasks(DebatePhaseId phaseId);

    List<DebateArtifact> revealedArtifacts(DebatePhaseId phaseId);

    void saveCandidates(List<DebateCandidate> candidates);

    List<DebateCandidate> candidates(DebateId debateId);

    void attachRevision(DebateCandidateId candidateId, DebateArtifactId revisionArtifactId);

    void saveBallots(List<ConsensusBallot> ballots);

    List<ConsensusBallot> ballots(DebateId debateId);

    void saveDecision(ConsensusDecision decision);

    Optional<ConsensusDecision> decision(DebateId debateId);
}
