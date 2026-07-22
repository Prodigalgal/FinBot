package io.omnnu.finbot.infrastructure.workflow.persistence;

import static io.omnnu.finbot.infrastructure.jdbc.persistence.PostgresJdbcParameters.timestamp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.workflow.exception.DebateProtocolConflictException;
import io.omnnu.finbot.application.workflow.port.out.DebateProtocolStore;
import io.omnnu.finbot.domain.consensus.AnonymousCandidateId;
import io.omnnu.finbot.domain.consensus.AnonymousPreferenceBallot;
import io.omnnu.finbot.domain.consensus.BallotOrientation;
import io.omnnu.finbot.domain.consensus.ConsensusBallot;
import io.omnnu.finbot.domain.consensus.ConsensusBallotId;
import io.omnnu.finbot.domain.consensus.ConsensusDecision;
import io.omnnu.finbot.domain.consensus.ConsensusDecisionId;
import io.omnnu.finbot.domain.consensus.ConsensusStatus;
import io.omnnu.finbot.domain.consensus.LogicalRoleKey;
import io.omnnu.finbot.domain.consensus.SchulzeOutcome;
import io.omnnu.finbot.domain.debate.DebateArtifact;
import io.omnnu.finbot.domain.debate.DebateArtifactId;
import io.omnnu.finbot.domain.debate.DebateArtifactStatus;
import io.omnnu.finbot.domain.debate.DebateCandidate;
import io.omnnu.finbot.domain.debate.DebateCandidateId;
import io.omnnu.finbot.domain.debate.DebatePhase;
import io.omnnu.finbot.domain.debate.DebatePhaseId;
import io.omnnu.finbot.domain.debate.DebatePhaseStatus;
import io.omnnu.finbot.domain.debate.DebatePhaseType;
import io.omnnu.finbot.domain.debate.DebateProtocol;
import io.omnnu.finbot.domain.debate.DebateTask;
import io.omnnu.finbot.domain.debate.DebateTaskId;
import io.omnnu.finbot.domain.debate.DebateTaskStatus;
import io.omnnu.finbot.domain.debate.DebateTaskVariant;
import io.omnnu.finbot.domain.workflow.DebateId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcDebateProtocolStore implements DebateProtocolStore {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcDebateProtocolStore(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    @Transactional
    public void createPhase(DebatePhase phase, List<DebateTask> tasks) {
        Objects.requireNonNull(phase, "phase");
        var immutableTasks = List.copyOf(tasks);
        if (immutableTasks.size() != phase.requiredTasks()) {
            throw new IllegalArgumentException("Phase requiredTasks must match the task seed count");
        }
        if (phase.protocol() != DebateProtocol.SDB_SCA_V1
                || phase.status() != DebatePhaseStatus.OPEN
                || phase.completedTasks() != 0
                || phase.version() != 0) {
            throw new IllegalArgumentException("A new SDB-SCA phase must start OPEN with empty progress");
        }
        if (immutableTasks.stream().anyMatch(task -> !task.phaseId().equals(phase.phaseId()))) {
            throw new IllegalArgumentException("Every debate task must belong to the created phase");
        }
        if (immutableTasks.stream().anyMatch(task -> task.status() != DebateTaskStatus.PENDING
                || task.attempt() != 0
                || task.leaseExpiresAt() != null
                || task.completedAt() != null)) {
            throw new IllegalArgumentException("New debate tasks must be unclaimed PENDING tasks");
        }
        var existing = findPhase(phase.phaseId());
        if (existing.isPresent()) {
            requireSamePhaseSeed(existing.orElseThrow(), phase, tasks(phase.phaseId()), immutableTasks);
            return;
        }
        var inserted = jdbcClient.sql("""
                insert into debate_protocol_phase (
                  phase_id, debate_id, protocol, generation, phase_type, status,
                  required_tasks, completed_tasks, deadline, opened_at, revealed_at,
                  completed_at, version
                ) values (
                  :phaseId, :debateId, :protocol, :generation, :phaseType, :status,
                  :requiredTasks, :completedTasks, :deadline, :openedAt, :revealedAt,
                  :completedAt, :version
                )
                on conflict do nothing
                """)
                .param("phaseId", phase.phaseId().value())
                .param("debateId", phase.debateId().value())
                .param("protocol", phase.protocol().name())
                .param("generation", phase.generation())
                .param("phaseType", phase.phaseType().name())
                .param("status", phase.status().name())
                .param("requiredTasks", phase.requiredTasks())
                .param("completedTasks", phase.completedTasks())
                .param("deadline", timestamp(phase.deadline()))
                .param("openedAt", nullableTimestamp(phase.openedAt()))
                .param("revealedAt", nullableTimestamp(phase.revealedAt()))
                .param("completedAt", nullableTimestamp(phase.completedAt()))
                .param("version", phase.version())
                .update();
        if (inserted == 0) {
            var persisted = findPhase(phase.phaseId()).orElseThrow(() ->
                    new DebateProtocolConflictException(
                            "A different phase already occupies the debate generation slot"));
            requireSamePhaseSeed(persisted, phase, tasks(phase.phaseId()), immutableTasks);
            return;
        }
        immutableTasks.forEach(this::insertTask);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DebatePhase> phase(DebatePhaseId phaseId) {
        Objects.requireNonNull(phaseId, "phaseId");
        return findPhase(phaseId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DebatePhase> currentPhase(DebateId debateId) {
        Objects.requireNonNull(debateId, "debateId");
        return jdbcClient.sql("""
                select phase_id, debate_id, protocol, generation, phase_type, status,
                       required_tasks, completed_tasks, deadline, opened_at, revealed_at,
                       completed_at, version
                from debate_protocol_phase
                where debate_id = :debateId
                order by generation desc,
                  case phase_type
                    when 'AGGREGATION' then 5
                    when 'BALLOT' then 4
                    when 'REVISION' then 3
                    when 'CRITIQUE' then 2
                    when 'PROPOSAL' then 1
                    else 0
                  end desc
                limit 1
                """)
                .param("debateId", debateId.value())
                .query((resultSet, rowNumber) -> phase(resultSet))
                .optional();
    }

    @Override
    @Transactional
    public List<DebateTask> claimTasks(
            DebatePhaseId phaseId,
            String leaseOwner,
            int maximumTasks,
            Duration leaseDuration,
            Instant now) {
        Objects.requireNonNull(phaseId, "phaseId");
        var normalizedOwner = Objects.requireNonNull(leaseOwner, "leaseOwner").strip();
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        Objects.requireNonNull(now, "now");
        if (normalizedOwner.isEmpty() || normalizedOwner.length() > 160) {
            throw new IllegalArgumentException("leaseOwner must contain 1 to 160 characters");
        }
        if (maximumTasks < 1 || maximumTasks > 64) {
            throw new IllegalArgumentException("maximumTasks must be between 1 and 64");
        }
        if (leaseDuration.isNegative() || leaseDuration.isZero()
                || leaseDuration.compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException("leaseDuration must be between zero and two hours");
        }
        timeoutExhaustedLeases(phaseId, now);
        return jdbcClient.sql("""
                with claimable as (
                  select task.id
                  from debate_protocol_task task
                  join debate_protocol_phase phase on phase.phase_id = task.phase_id
                  where task.phase_id = :phaseId
                    and phase.status = 'OPEN'
                    and phase.deadline > :now
                    and task.attempt < 5
                    and (
                      task.status = 'PENDING'
                      or (task.status = 'CLAIMED' and task.lease_expires_at <= :now)
                    )
                  order by task.id
                  for update of task skip locked
                  limit :maximumTasks
                )
                update debate_protocol_task task
                set status = 'CLAIMED',
                    attempt = task.attempt + 1,
                    lease_owner = :leaseOwner,
                    lease_expires_at = :leaseExpiresAt
                from claimable
                where task.id = claimable.id
                returning task.task_id, task.phase_id, task.actor_node_id,
                          task.logical_role_key, task.target_candidate_id, task.input_hash, task.status,
                          task.task_variant, task.attempt, task.lease_owner, task.lease_expires_at,
                          task.created_at, task.completed_at
                """)
                .param("phaseId", phaseId.value())
                .param("now", timestamp(now))
                .param("maximumTasks", maximumTasks)
                .param("leaseOwner", normalizedOwner)
                .param("leaseExpiresAt", timestamp(now.plus(leaseDuration)))
                .query((resultSet, rowNumber) -> task(resultSet))
                .list();
    }

    @Override
    @Transactional
    public void sealArtifact(DebateArtifact artifact, String leaseOwner, Instant completedAt) {
        Objects.requireNonNull(artifact, "artifact");
        var normalizedLeaseOwner = required(leaseOwner, "leaseOwner", 160);
        Objects.requireNonNull(completedAt, "completedAt");
        if (artifact.status() != DebateArtifactStatus.SEALED || artifact.revealedAt() != null) {
            throw new IllegalArgumentException("Only a SEALED artifact can complete a debate task");
        }
        requireJson(artifact.content());
        var task = lockTask(artifact.taskId());
        if (!task.phaseId().equals(artifact.phaseId())) {
            throw new DebateProtocolConflictException("Artifact phase does not match its debate task");
        }
        if (!sha256(artifact.content()).equals(artifact.contentHash())) {
            throw new DebateProtocolConflictException(
                    "Artifact content hash does not match its canonical content");
        }
        var existingArtifact = jdbcClient.sql("""
                select artifact_id, content_hash from debate_protocol_artifact where task_id = :taskId
                """)
                .param("taskId", artifact.taskId().value())
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getString("artifact_id"), resultSet.getString("content_hash")))
                .optional();
        if (existingArtifact.isPresent()) {
            var persisted = existingArtifact.orElseThrow();
            if (persisted.get(0).equals(artifact.artifactId().value())
                    && persisted.get(1).equals(artifact.contentHash())) {
                return;
            }
            throw new DebateProtocolConflictException(
                    "The deterministic debate task already has a different artifact hash");
        }
        requireLease(task, normalizedLeaseOwner);
        if (task.status() == DebateTaskStatus.COMPLETED) {
            throw new DebateProtocolConflictException("Completed debate task has no persisted artifact");
        }
        if (terminal(task.status())) {
            throw new DebateProtocolConflictException("A terminal debate task cannot seal an artifact");
        }
        jdbcClient.sql("""
                insert into debate_protocol_artifact (
                  artifact_id, task_id, phase_id, status, content_hash, content,
                  sealed_at, revealed_at
                ) values (
                  :artifactId, :taskId, :phaseId, 'SEALED', :contentHash,
                  cast(:content as jsonb), :sealedAt, null
                )
                """)
                .param("artifactId", artifact.artifactId().value())
                .param("taskId", artifact.taskId().value())
                .param("phaseId", artifact.phaseId().value())
                .param("contentHash", artifact.contentHash())
                .param("content", artifact.content())
                .param("sealedAt", timestamp(artifact.sealedAt()))
                .update();
        completeTaskAndIncrementBarrier(artifact.taskId(), artifact.phaseId(), completedAt);
    }

    @Override
    @Transactional
    public void failTask(
            DebateTaskId taskId,
            String leaseOwner,
            String errorCode,
            String errorMessage,
            Instant completedAt) {
        Objects.requireNonNull(completedAt, "completedAt");
        var normalizedLeaseOwner = required(leaseOwner, "leaseOwner", 160);
        var task = lockTask(taskId);
        if (task.status() == DebateTaskStatus.COMPLETED) {
            throw new DebateProtocolConflictException("A completed debate task cannot fail");
        }
        if (terminal(task.status())) {
            return;
        }
        requireLease(task, normalizedLeaseOwner);
        var code = required(errorCode, "errorCode", 80);
        var message = required(errorMessage, "errorMessage", 8_000);
        jdbcClient.sql("""
                update debate_protocol_task
                set status = 'FAILED', lease_owner = null, lease_expires_at = null,
                    error_code = :errorCode, error_message = :errorMessage,
                    completed_at = :completedAt
                where task_id = :taskId
                """)
                .param("taskId", taskId.value())
                .param("errorCode", code)
                .param("errorMessage", message)
                .param("completedAt", timestamp(completedAt))
                .update();
        incrementBarrier(task.phaseId());
    }

    @Override
    @Transactional
    public void timeoutTask(DebateTaskId taskId, Instant completedAt) {
        Objects.requireNonNull(completedAt, "completedAt");
        var task = lockTask(taskId);
        if (terminal(task.status())) {
            return;
        }
        var deadline = jdbcClient.sql("""
                select deadline from debate_protocol_phase where phase_id = :phaseId
                """)
                .param("phaseId", task.phaseId().value())
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
        if (completedAt.isBefore(deadline)) {
            throw new DebateProtocolConflictException(
                    "A debate task cannot time out before its phase deadline");
        }
        jdbcClient.sql("""
                update debate_protocol_task
                set status = 'TIMED_OUT', lease_owner = null, lease_expires_at = null,
                    error_code = 'SDB_PHASE_DEADLINE_EXCEEDED',
                    error_message = 'SDB-SCA phase deadline expired before task completion',
                    completed_at = :completedAt
                where task_id = :taskId and status in ('PENDING', 'CLAIMED')
                """)
                .param("taskId", taskId.value())
                .param("completedAt", timestamp(completedAt))
                .update();
        incrementBarrier(task.phaseId());
    }

    @Override
    @Transactional
    public boolean revealPhase(DebatePhaseId phaseId, long expectedVersion, Instant revealedAt) {
        Objects.requireNonNull(phaseId, "phaseId");
        Objects.requireNonNull(revealedAt, "revealedAt");
        var phase = jdbcClient.sql("""
                select phase_id, debate_id, protocol, generation, phase_type, status,
                       required_tasks, completed_tasks, deadline, opened_at, revealed_at,
                       completed_at, version
                from debate_protocol_phase
                where phase_id = :phaseId
                for update
                """)
                .param("phaseId", phaseId.value())
                .query((resultSet, rowNumber) -> phase(resultSet))
                .optional()
                .orElseThrow(() -> new DebateProtocolConflictException("Debate phase does not exist"));
        if (phase.status() == DebatePhaseStatus.REVEALED
                || phase.status() == DebatePhaseStatus.COMPLETED) {
            return true;
        }
        if (phase.version() != expectedVersion
                || phase.status() != DebatePhaseStatus.OPEN
                || !phase.barrierSatisfied()) {
            return false;
        }
        jdbcClient.sql("""
                update debate_protocol_artifact
                set status = 'REVEALED', revealed_at = :revealedAt
                where phase_id = :phaseId and status = 'SEALED'
                """)
                .param("phaseId", phaseId.value())
                .param("revealedAt", timestamp(revealedAt))
                .update();
        var updated = jdbcClient.sql("""
                update debate_protocol_phase
                set status = 'REVEALED', revealed_at = :revealedAt, version = version + 1
                where phase_id = :phaseId and version = :expectedVersion and status = 'OPEN'
                """)
                .param("phaseId", phaseId.value())
                .param("expectedVersion", expectedVersion)
                .param("revealedAt", timestamp(revealedAt))
                .update();
        if (updated != 1) {
            throw new DebateProtocolConflictException(
                    "Debate phase reveal CAS failed while holding the phase lock");
        }
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DebateTask> tasks(DebatePhaseId phaseId) {
        return jdbcClient.sql("""
                select task_id, phase_id, actor_node_id, target_candidate_id,
                       logical_role_key, task_variant, input_hash, status, attempt,
                       lease_owner, lease_expires_at, created_at, completed_at
                from debate_protocol_task
                where phase_id = :phaseId
                order by actor_node_id, target_candidate_id nulls first, id
                """)
                .param("phaseId", phaseId.value())
                .query((resultSet, rowNumber) -> task(resultSet))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DebateArtifact> revealedArtifacts(DebatePhaseId phaseId) {
        return jdbcClient.sql("""
                select artifact_id, task_id, phase_id, status, content_hash,
                       content::text as content, sealed_at, revealed_at
                from debate_protocol_artifact
                where phase_id = :phaseId and status = 'REVEALED'
                order by id
                """)
                .param("phaseId", phaseId.value())
                .query((resultSet, rowNumber) -> artifact(resultSet))
                .list();
    }

    @Override
    @Transactional
    public void saveCandidates(List<DebateCandidate> candidates) {
        var requested = List.copyOf(candidates);
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("candidates must not be empty");
        }
        var debateId = requested.getFirst().debateId();
        if (requested.stream().anyMatch(candidate -> !candidate.debateId().equals(debateId))) {
            throw new IllegalArgumentException("All candidates must belong to the same debate");
        }
        for (var candidate : requested) {
            jdbcClient.sql("""
                    insert into debate_candidate (
                      candidate_id, debate_id, origin_node_id, logical_role_key,
                      anonymous_alias, proposal_artifact_id, revision_artifact_id, created_at
                    ) values (
                      :candidateId, :debateId, :originNodeId, :logicalRoleKey,
                      :anonymousAlias, :proposalArtifactId, :revisionArtifactId, :createdAt
                    )
                    on conflict (candidate_id) do nothing
                    """)
                    .param("candidateId", candidate.candidateId().value())
                    .param("debateId", candidate.debateId().value())
                    .param("originNodeId", candidate.originNodeId().value())
                    .param("logicalRoleKey", candidate.logicalRoleKey().value())
                    .param("anonymousAlias", candidate.anonymousCandidateId().value())
                    .param("proposalArtifactId", candidate.proposalArtifactId().value())
                    .param("revisionArtifactId", candidate.revisionArtifactId() == null
                            ? null
                            : candidate.revisionArtifactId().value())
                    .param("createdAt", timestamp(candidate.createdAt()))
                    .update();
        }
        var persisted = candidates(debateId);
        var persistedSeeds = persisted.stream()
                .map(JdbcDebateProtocolStore::candidateSeed)
                .sorted()
                .toList();
        var requestedSeeds = requested.stream()
                .map(JdbcDebateProtocolStore::candidateSeed)
                .sorted()
                .toList();
        if (!persistedSeeds.equals(requestedSeeds)) {
            throw new DebateProtocolConflictException(
                    "The debate already has a different anonymous candidate mapping");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<DebateCandidate> candidates(DebateId debateId) {
        return jdbcClient.sql("""
                select candidate_id, debate_id, origin_node_id, logical_role_key,
                       anonymous_alias, proposal_artifact_id, revision_artifact_id, created_at
                from debate_candidate
                where debate_id = :debateId
                order by anonymous_alias, id
                """)
                .param("debateId", debateId.value())
                .query((resultSet, rowNumber) -> candidate(resultSet))
                .list();
    }

    @Override
    @Transactional
    public void attachRevision(
            DebateCandidateId candidateId,
            DebateArtifactId revisionArtifactId) {
        var updated = jdbcClient.sql("""
                update debate_candidate
                set revision_artifact_id = :revisionArtifactId
                where candidate_id = :candidateId
                  and (revision_artifact_id is null or revision_artifact_id = :revisionArtifactId)
                """)
                .param("candidateId", candidateId.value())
                .param("revisionArtifactId", revisionArtifactId.value())
                .update();
        if (updated != 1) {
            throw new DebateProtocolConflictException(
                    "The debate candidate already references a different revision artifact");
        }
    }

    @Override
    @Transactional
    public void saveBallots(List<ConsensusBallot> ballots) {
        var requested = List.copyOf(ballots);
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("ballots must not be empty");
        }
        var debateId = requested.getFirst().debateId();
        if (requested.stream().anyMatch(ballot -> !ballot.debateId().equals(debateId))) {
            throw new IllegalArgumentException("All ballots must belong to the same debate");
        }
        for (var ballot : requested) {
            jdbcClient.sql("""
                    insert into consensus_ballot (
                      ballot_id, debate_id, phase_id, actor_node_id, logical_role_key,
                      orientation, preference_tiers, content_hash, created_at
                    ) values (
                      :ballotId, :debateId, :phaseId, :actorNodeId, :logicalRoleKey,
                      :orientation, cast(:preferenceTiers as jsonb), :contentHash, :createdAt
                    )
                    on conflict do nothing
                    """)
                    .param("ballotId", ballot.ballotId().value())
                    .param("debateId", ballot.debateId().value())
                    .param("phaseId", ballot.phaseId().value())
                    .param("actorNodeId", ballot.actorNodeId().value())
                    .param("logicalRoleKey", ballot.preference().logicalRoleKey().value())
                    .param("orientation", ballot.preference().orientation().name())
                    .param("preferenceTiers", preferenceTiersJson(ballot.preference()))
                    .param("contentHash", ballot.contentHash())
                    .param("createdAt", timestamp(ballot.createdAt()))
                    .update();
        }
        var persistedSeeds = ballots(debateId).stream()
                .map(this::ballotSeed)
                .sorted()
                .toList();
        var requestedSeeds = requested.stream()
                .map(this::ballotSeed)
                .sorted()
                .toList();
        if (!persistedSeeds.equals(requestedSeeds)) {
            throw new DebateProtocolConflictException(
                    "The debate already has a different immutable ballot set");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsensusBallot> ballots(DebateId debateId) {
        Objects.requireNonNull(debateId, "debateId");
        return jdbcClient.sql("""
                select ballot_id, debate_id, phase_id, actor_node_id, logical_role_key,
                       orientation, preference_tiers::text as preference_tiers,
                       content_hash, created_at
                from consensus_ballot
                where debate_id = :debateId
                order by logical_role_key, actor_node_id, orientation, id
                """)
                .param("debateId", debateId.value())
                .query((resultSet, rowNumber) -> ballot(resultSet))
                .list();
    }

    @Override
    @Transactional
    public void saveDecision(ConsensusDecision decision) {
        Objects.requireNonNull(decision, "decision");
        requireJson(decision.pairwiseMatrixJson());
        requireJson(decision.strongestPathsJson());
        requireJson(decision.rankingJson());
        if (decision.forecastJson() != null) {
            requireJson(decision.forecastJson());
        }
        jdbcClient.sql("""
                insert into consensus_decision (
                  decision_id, debate_id, status, winner_candidate_id, quorum_roles,
                  undefeated_candidates, pairwise_matrix, strongest_paths, ranking,
                  forecast, explanation, decision_hash, decided_at
                ) values (
                  :decisionId, :debateId, :status, :winnerCandidateId, :quorumRoles,
                  cast(:undefeatedCandidates as jsonb), cast(:pairwiseMatrix as jsonb),
                  cast(:strongestPaths as jsonb), cast(:ranking as jsonb),
                  cast(:forecast as jsonb), :explanation, :decisionHash, :decidedAt
                )
                on conflict do nothing
                """)
                .param("decisionId", decision.decisionId().value())
                .param("debateId", decision.debateId().value())
                .param("status", decision.outcome().status().name())
                .param("winnerCandidateId", decision.winnerCandidateId() == null
                        ? null
                        : decision.winnerCandidateId().value())
                .param("quorumRoles", decision.outcome().contributingRoleCount())
                .param("undefeatedCandidates", anonymousCandidateIdsJson(
                        decision.outcome().undefeatedCandidates()))
                .param("pairwiseMatrix", decision.pairwiseMatrixJson())
                .param("strongestPaths", decision.strongestPathsJson())
                .param("ranking", decision.rankingJson())
                .param("forecast", decision.forecastJson())
                .param("explanation", decision.explanation())
                .param("decisionHash", decision.decisionHash())
                .param("decidedAt", timestamp(decision.decidedAt()))
                .update();
        var persisted = decision(decision.debateId()).orElseThrow(() ->
                new DebateProtocolConflictException("Consensus decision was not persisted"));
        if (!sameDecision(persisted, decision)) {
            throw new DebateProtocolConflictException(
                    "The debate already has a different immutable consensus decision");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConsensusDecision> decision(DebateId debateId) {
        Objects.requireNonNull(debateId, "debateId");
        return jdbcClient.sql("""
                select decision.decision_id, decision.debate_id, decision.status,
                       decision.winner_candidate_id, candidate.anonymous_alias,
                       decision.quorum_roles,
                       decision.undefeated_candidates::text as undefeated_candidates,
                       decision.pairwise_matrix::text as pairwise_matrix,
                       decision.strongest_paths::text as strongest_paths,
                       decision.ranking::text as ranking,
                       decision.forecast::text as forecast,
                       decision.explanation, decision.decision_hash, decision.decided_at
                from consensus_decision decision
                left join debate_candidate candidate
                  on candidate.candidate_id = decision.winner_candidate_id
                where decision.debate_id = :debateId
                """)
                .param("debateId", debateId.value())
                .query((resultSet, rowNumber) -> decision(resultSet))
                .optional();
    }

    private void insertTask(DebateTask task) {
        jdbcClient.sql("""
                insert into debate_protocol_task (
                  task_id, phase_id, actor_node_id, logical_role_key,
                  target_candidate_id, task_variant, input_hash, status, attempt,
                  lease_owner, lease_expires_at, created_at, completed_at
                ) values (
                  :taskId, :phaseId, :actorNodeId, :logicalRoleKey,
                  :targetCandidateId, :taskVariant, :inputHash, :status, :attempt,
                  null, :leaseExpiresAt, :createdAt, :completedAt
                )
                """)
                .param("taskId", task.taskId().value())
                .param("phaseId", task.phaseId().value())
                .param("actorNodeId", task.actorNodeId().value())
                .param("logicalRoleKey", task.logicalRoleKey().value())
                .param("targetCandidateId", task.targetCandidateId())
                .param("taskVariant", task.variant().name())
                .param("inputHash", task.inputHash())
                .param("status", task.status().name())
                .param("attempt", task.attempt())
                .param("leaseExpiresAt", nullableTimestamp(task.leaseExpiresAt()))
                .param("createdAt", timestamp(task.createdAt()))
                .param("completedAt", nullableTimestamp(task.completedAt()))
                .update();
    }

    private Optional<DebatePhase> findPhase(DebatePhaseId phaseId) {
        return jdbcClient.sql("""
                select phase_id, debate_id, protocol, generation, phase_type, status,
                       required_tasks, completed_tasks, deadline, opened_at, revealed_at,
                       completed_at, version
                from debate_protocol_phase
                where phase_id = :phaseId
                """)
                .param("phaseId", phaseId.value())
                .query((resultSet, rowNumber) -> phase(resultSet))
                .optional();
    }

    private static void requireSamePhaseSeed(
            DebatePhase persisted,
            DebatePhase requested,
            List<DebateTask> persistedTasks,
            List<DebateTask> requestedTasks) {
        var samePhase = persisted.phaseId().equals(requested.phaseId())
                && persisted.debateId().equals(requested.debateId())
                && persisted.protocol() == requested.protocol()
                && persisted.generation() == requested.generation()
                && persisted.phaseType() == requested.phaseType()
                && persisted.requiredTasks() == requested.requiredTasks()
                && persisted.deadline().equals(requested.deadline());
        var persistedSeeds = persistedTasks.stream()
                .map(JdbcDebateProtocolStore::taskSeed)
                .sorted()
                .toList();
        var requestedSeeds = requestedTasks.stream()
                .map(JdbcDebateProtocolStore::taskSeed)
                .sorted()
                .toList();
        if (!samePhase || !persistedSeeds.equals(requestedSeeds)) {
            throw new DebateProtocolConflictException(
                    "The deterministic debate phase already exists with a different seed");
        }
    }

    private static String taskSeed(DebateTask task) {
        return String.join(
                "\u001f",
                task.taskId().value(),
                task.phaseId().value(),
                task.actorNodeId().value(),
                task.logicalRoleKey().value(),
                task.targetCandidateId() == null ? "" : task.targetCandidateId(),
                task.variant().name(),
                task.inputHash());
    }

    private static String candidateSeed(DebateCandidate candidate) {
        return String.join(
                "\u001f",
                candidate.candidateId().value(),
                candidate.debateId().value(),
                candidate.originNodeId().value(),
                candidate.logicalRoleKey().value(),
                candidate.anonymousCandidateId().value(),
                candidate.proposalArtifactId().value());
    }

    private String ballotSeed(ConsensusBallot ballot) {
        return String.join(
                "\u001f",
                ballot.ballotId().value(),
                ballot.debateId().value(),
                ballot.phaseId().value(),
                ballot.actorNodeId().value(),
                ballot.preference().logicalRoleKey().value(),
                ballot.preference().orientation().name(),
                preferenceTiersJson(ballot.preference()),
                ballot.contentHash());
    }

    private boolean sameDecision(ConsensusDecision left, ConsensusDecision right) {
        return left.decisionId().equals(right.decisionId())
                && left.debateId().equals(right.debateId())
                && left.outcome().status() == right.outcome().status()
                && Objects.equals(left.outcome().selectedCandidate(), right.outcome().selectedCandidate())
                && left.outcome().undefeatedCandidates().equals(right.outcome().undefeatedCandidates())
                && left.outcome().contributingRoleCount() == right.outcome().contributingRoleCount()
                && Objects.equals(left.winnerCandidateId(), right.winnerCandidateId())
                && sameJson(left.pairwiseMatrixJson(), right.pairwiseMatrixJson())
                && sameJson(left.strongestPathsJson(), right.strongestPathsJson())
                && sameJson(left.rankingJson(), right.rankingJson())
                && sameNullableJson(left.forecastJson(), right.forecastJson())
                && Objects.equals(left.explanation(), right.explanation())
                && left.decisionHash().equals(right.decisionHash());
    }

    private boolean sameJson(String left, String right) {
        try {
            return objectMapper.readTree(left).equals(objectMapper.readTree(right));
        } catch (JsonProcessingException exception) {
            throw new DebateProtocolConflictException("Consensus decision contains invalid JSON", exception);
        }
    }

    private boolean sameNullableJson(String left, String right) {
        return left == null || right == null
                ? left == null && right == null
                : sameJson(left, right);
    }

    private DebateTask lockTask(DebateTaskId taskId) {
        return jdbcClient.sql("""
                select task_id, phase_id, actor_node_id, target_candidate_id,
                       logical_role_key, task_variant, input_hash, status, attempt,
                       lease_owner, lease_expires_at, created_at, completed_at
                from debate_protocol_task
                where task_id = :taskId
                for update
                """)
                .param("taskId", taskId.value())
                .query((resultSet, rowNumber) -> task(resultSet))
                .optional()
                .orElseThrow(() -> new DebateProtocolConflictException("Debate task does not exist"));
    }

    private void completeTaskAndIncrementBarrier(
            DebateTaskId taskId,
            DebatePhaseId phaseId,
            Instant completedAt) {
        var updated = jdbcClient.sql("""
                update debate_protocol_task
                set status = 'COMPLETED', lease_owner = null, lease_expires_at = null,
                    completed_at = :completedAt
                where task_id = :taskId and status in ('PENDING', 'CLAIMED')
                """)
                .param("taskId", taskId.value())
                .param("completedAt", timestamp(completedAt))
                .update();
        if (updated != 1) {
            throw new DebateProtocolConflictException("Debate task could not transition to COMPLETED");
        }
        incrementBarrier(phaseId);
    }

    private void incrementBarrier(DebatePhaseId phaseId) {
        var updated = jdbcClient.sql("""
                update debate_protocol_phase
                set completed_tasks = completed_tasks + 1, version = version + 1
                where phase_id = :phaseId
                  and status = 'OPEN'
                  and completed_tasks < required_tasks
                """)
                .param("phaseId", phaseId.value())
                .update();
        if (updated != 1) {
            throw new DebateProtocolConflictException("Debate phase barrier counter update failed");
        }
    }

    private DebatePhase phase(ResultSet resultSet) throws SQLException {
        return new DebatePhase(
                new DebatePhaseId(resultSet.getString("phase_id")),
                new DebateId(resultSet.getString("debate_id")),
                DebateProtocol.valueOf(resultSet.getString("protocol")),
                resultSet.getInt("generation"),
                DebatePhaseType.valueOf(resultSet.getString("phase_type")),
                DebatePhaseStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("required_tasks"),
                resultSet.getInt("completed_tasks"),
                instant(resultSet.getObject("deadline", OffsetDateTime.class)),
                nullableInstant(resultSet.getObject("opened_at", OffsetDateTime.class)),
                nullableInstant(resultSet.getObject("revealed_at", OffsetDateTime.class)),
                nullableInstant(resultSet.getObject("completed_at", OffsetDateTime.class)),
                resultSet.getLong("version"));
    }

    private DebateTask task(ResultSet resultSet) throws SQLException {
        return new DebateTask(
                new DebateTaskId(resultSet.getString("task_id")),
                new DebatePhaseId(resultSet.getString("phase_id")),
                new WorkflowNodeId(resultSet.getString("actor_node_id")),
                new LogicalRoleKey(resultSet.getString("logical_role_key")),
                resultSet.getString("target_candidate_id"),
                DebateTaskVariant.valueOf(resultSet.getString("task_variant")),
                resultSet.getString("input_hash"),
                DebateTaskStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempt"),
                resultSet.getString("lease_owner"),
                nullableInstant(resultSet.getObject("lease_expires_at", OffsetDateTime.class)),
                instant(resultSet.getObject("created_at", OffsetDateTime.class)),
                nullableInstant(resultSet.getObject("completed_at", OffsetDateTime.class)));
    }

    private DebateArtifact artifact(ResultSet resultSet) throws SQLException {
        return new DebateArtifact(
                new DebateArtifactId(resultSet.getString("artifact_id")),
                new DebateTaskId(resultSet.getString("task_id")),
                new DebatePhaseId(resultSet.getString("phase_id")),
                DebateArtifactStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("content_hash"),
                resultSet.getString("content"),
                instant(resultSet.getObject("sealed_at", OffsetDateTime.class)),
                nullableInstant(resultSet.getObject("revealed_at", OffsetDateTime.class)));
    }

    private DebateCandidate candidate(ResultSet resultSet) throws SQLException {
        var revisionArtifactId = resultSet.getString("revision_artifact_id");
        return new DebateCandidate(
                new DebateCandidateId(resultSet.getString("candidate_id")),
                new DebateId(resultSet.getString("debate_id")),
                new WorkflowNodeId(resultSet.getString("origin_node_id")),
                new LogicalRoleKey(resultSet.getString("logical_role_key")),
                new AnonymousCandidateId(resultSet.getString("anonymous_alias")),
                new DebateArtifactId(resultSet.getString("proposal_artifact_id")),
                revisionArtifactId == null ? null : new DebateArtifactId(revisionArtifactId),
                instant(resultSet.getObject("created_at", OffsetDateTime.class)));
    }

    private ConsensusBallot ballot(ResultSet resultSet) throws SQLException {
        var logicalRoleKey = new LogicalRoleKey(resultSet.getString("logical_role_key"));
        var orientation = BallotOrientation.valueOf(resultSet.getString("orientation"));
        return new ConsensusBallot(
                new ConsensusBallotId(resultSet.getString("ballot_id")),
                new DebateId(resultSet.getString("debate_id")),
                new DebatePhaseId(resultSet.getString("phase_id")),
                new WorkflowNodeId(resultSet.getString("actor_node_id")),
                AnonymousPreferenceBallot.of(
                        logicalRoleKey,
                        orientation,
                        parsePreferenceTiers(resultSet.getString("preference_tiers"))),
                resultSet.getString("content_hash"),
                instant(resultSet.getObject("created_at", OffsetDateTime.class)));
    }

    private ConsensusDecision decision(ResultSet resultSet) throws SQLException {
        var status = ConsensusStatus.valueOf(resultSet.getString("status"));
        var undefeatedCandidates = parseAnonymousCandidateIds(
                resultSet.getString("undefeated_candidates"));
        var winnerCandidateId = resultSet.getString("winner_candidate_id");
        var winnerAlias = resultSet.getString("anonymous_alias");
        var quorumRoles = resultSet.getInt("quorum_roles");
        var outcome = status == ConsensusStatus.SELECTED
                ? SchulzeOutcome.selected(
                        new AnonymousCandidateId(winnerAlias), undefeatedCandidates, quorumRoles)
                : SchulzeOutcome.unsuccessful(status, undefeatedCandidates, quorumRoles);
        return new ConsensusDecision(
                new ConsensusDecisionId(resultSet.getString("decision_id")),
                new DebateId(resultSet.getString("debate_id")),
                outcome,
                winnerCandidateId == null ? null : new DebateCandidateId(winnerCandidateId),
                resultSet.getString("pairwise_matrix"),
                resultSet.getString("strongest_paths"),
                resultSet.getString("ranking"),
                resultSet.getString("forecast"),
                resultSet.getString("explanation"),
                resultSet.getString("decision_hash"),
                instant(resultSet.getObject("decided_at", OffsetDateTime.class)));
    }

    private String preferenceTiersJson(AnonymousPreferenceBallot preference) {
        var tiers = preference.preferenceTiers().stream()
                .map(tier -> tier.stream()
                        .map(AnonymousCandidateId::value)
                        .sorted()
                        .toList())
                .toList();
        return writeJson(tiers, "preference tiers");
    }

    private String anonymousCandidateIdsJson(List<AnonymousCandidateId> candidateIds) {
        return writeJson(
                candidateIds.stream().map(AnonymousCandidateId::value).toList(),
                "anonymous candidate identifiers");
    }

    private List<List<AnonymousCandidateId>> parsePreferenceTiers(String json) {
        try {
            var values = objectMapper.readValue(json, new TypeReference<List<List<String>>>() {});
            return values.stream()
                    .map(tier -> tier.stream().map(AnonymousCandidateId::new).toList())
                    .toList();
        } catch (JsonProcessingException exception) {
            throw new DebateProtocolConflictException("Stored preference tiers are invalid JSON", exception);
        }
    }

    private List<AnonymousCandidateId> parseAnonymousCandidateIds(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {}).stream()
                    .map(AnonymousCandidateId::new)
                    .toList();
        } catch (JsonProcessingException exception) {
            throw new DebateProtocolConflictException(
                    "Stored anonymous candidate identifiers are invalid JSON", exception);
        }
    }

    private String writeJson(Object value, String description) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(description + " could not be serialized", exception);
        }
    }

    private void requireJson(String content) {
        try {
            objectMapper.readTree(content);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Debate artifact content must be valid JSON", exception);
        }
    }

    private void timeoutExhaustedLeases(DebatePhaseId phaseId, Instant now) {
        var timedOutTaskIds = jdbcClient.sql("""
                update debate_protocol_task task
                set status = 'TIMED_OUT', lease_owner = null, lease_expires_at = null,
                    error_code = 'SDB_TASK_RETRY_EXHAUSTED',
                    error_message = 'SDB-SCA task exhausted its five lease attempts',
                    completed_at = :now
                from debate_protocol_phase phase
                where task.phase_id = :phaseId
                  and phase.phase_id = task.phase_id
                  and phase.status = 'OPEN'
                  and task.status = 'CLAIMED'
                  and task.attempt >= 5
                  and task.lease_expires_at <= :now
                returning task.task_id
                """)
                .param("phaseId", phaseId.value())
                .param("now", timestamp(now))
                .query(String.class)
                .list();
        if (!timedOutTaskIds.isEmpty()) {
            jdbcClient.sql("""
                    update debate_protocol_phase
                    set completed_tasks = completed_tasks + :count, version = version + 1
                    where phase_id = :phaseId and status = 'OPEN'
                    """)
                    .param("count", timedOutTaskIds.size())
                    .param("phaseId", phaseId.value())
                    .update();
        }
    }

    private static void requireLease(DebateTask task, String leaseOwner) {
        if (task.status() != DebateTaskStatus.CLAIMED
                || !Objects.equals(task.leaseOwner(), leaseOwner)) {
            throw new DebateProtocolConflictException(
                    "Debate task completion requires ownership of its active lease");
        }
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean terminal(DebateTaskStatus status) {
        return status == DebateTaskStatus.COMPLETED
                || status == DebateTaskStatus.FAILED
                || status == DebateTaskStatus.TIMED_OUT
                || status == DebateTaskStatus.CANCELLED;
    }

    private static String required(String value, String fieldName, int maximumLength) {
        var normalized = Objects.requireNonNull(value, fieldName).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    fieldName + " must contain 1 to " + maximumLength + " characters");
        }
        return normalized;
    }

    private static OffsetDateTime nullableTimestamp(Instant instant) {
        return instant == null ? null : timestamp(instant);
    }

    private static Instant instant(OffsetDateTime value) {
        return Objects.requireNonNull(value, "database timestamp").toInstant();
    }

    private static Instant nullableInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
