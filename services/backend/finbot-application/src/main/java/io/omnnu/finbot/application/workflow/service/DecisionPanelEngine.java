package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.application.ai.service.AiExecutionPolicyExecutor;
import io.omnnu.finbot.application.workflow.dto.DebateSession;
import io.omnnu.finbot.application.workflow.dto.DecisionPanelCandidateView;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.application.workflow.exception.SdbScaExecutionException;
import io.omnnu.finbot.application.workflow.port.out.DebateProtocolStore;
import io.omnnu.finbot.application.workflow.port.out.WorkflowExecutionStore;
import io.omnnu.finbot.domain.consensus.AnonymousCandidateId;
import io.omnnu.finbot.domain.consensus.BallotOrientation;
import io.omnnu.finbot.domain.consensus.ConsensusBallot;
import io.omnnu.finbot.domain.consensus.SchulzeConsensusEngine;
import io.omnnu.finbot.domain.debate.CritiqueAssignmentPolicy;
import io.omnnu.finbot.domain.debate.DebateArtifact;
import io.omnnu.finbot.domain.debate.DebateCandidate;
import io.omnnu.finbot.domain.debate.DebatePhaseType;
import io.omnnu.finbot.domain.debate.DebateTask;
import io.omnnu.finbot.domain.debate.DebateTaskStatus;
import io.omnnu.finbot.domain.debate.DebateTaskVariant;
import io.omnnu.finbot.domain.workflow.DebateStatus;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DecisionPanelEngine {
    private static final int GENERATION = 1;

    private final DebateProtocolStore protocolStore;
    private final Clock clock;
    private final SdbScaPhaseExecutor phaseExecutor;
    private final DecisionPanelSessionService panelSessions;
    private final WorkflowConditionEvaluator conditionEvaluator = new WorkflowConditionEvaluator();
    private final SchulzeConsensusEngine consensusEngine = new SchulzeConsensusEngine();

    public DecisionPanelEngine(
            WorkflowExecutionStore executionStore,
            DebateProtocolStore protocolStore,
            AiExecutionPolicyExecutor aiExecution,
            Clock clock,
            Executor executor) {
        Objects.requireNonNull(executionStore, "executionStore");
        this.protocolStore = Objects.requireNonNull(protocolStore, "protocolStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.panelSessions = new DecisionPanelSessionService(executionStore, this.clock);
        this.phaseExecutor = new SdbScaPhaseExecutor(
                this.protocolStore,
                Objects.requireNonNull(aiExecution, "aiExecution"),
                this.clock,
                Objects.requireNonNull(executor, "executor"));
    }

    public <R> R execute(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition decisionNode,
            List<WorkflowNodeDefinition> participantNodes,
            DecisionPanelProtocolDriver<R> driver) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(decisionNode, "decisionNode");
        Objects.requireNonNull(participantNodes, "participantNodes");
        Objects.requireNonNull(driver, "driver");

        var session = panelSessions.ensure(
                execution,
                driver.panelKey(),
                driver.purpose(),
                decisionNode.nodeId(),
                1);
        var frozenExecution = DecisionPanelSessionService.restoreFrozenInput(execution, session);

        if (session.status() == DebateStatus.COMPLETED || session.status() == DebateStatus.PARTIAL) {
            var recovered = driver.recoverCompletedResult(session, decisionNode);
            if (recovered.isPresent()) {
                return recovered.get();
            }
            throw new SdbScaExecutionException(
                    "SDB_RECOVERY_RESULT_MISSING",
                    "Completed SDB-SCA panel has no persisted consensus result",
                    false);
        }

        var configuredParticipants = participantNodes.stream()
                .filter(WorkflowNodeDefinition::enabled)
                .filter(node -> node.nodeType() == WorkflowNodeType.AGENT
                        || node.nodeType() == WorkflowNodeType.AGGREGATOR)
                .filter(node -> conditionEvaluator.isActive(frozenExecution, node, 1, List.of()))
                .sorted(Comparator.comparing(node -> node.nodeId().value()))
                .toList();

        var configuration = frozenExecution.definitionVersion().debateProtocolConfiguration();
        var identityGuard = new SdbScaIdentityDisclosureGuard(configuredParticipants);
        if (configuredParticipants.size() < configuration.minimumParticipantSeats()) {
            return driver.lowQuorumResult(frozenExecution, session, decisionNode, List.of(), 0);
        }

        var proposalCommands = configuredParticipants.stream()
                .map(node -> driver.proposalCommand(frozenExecution, node, identityGuard))
                .toList();
        var proposal = phaseExecutor.execute(
                frozenExecution,
                session,
                GENERATION,
                DebatePhaseType.PROPOSAL,
                proposalCommands);

        var nodesById = configuredParticipants.stream().collect(Collectors.toUnmodifiableMap(
                WorkflowNodeDefinition::nodeId,
                Function.identity()));
        var proposalArtifactsByTask = artifactsByTask(proposal.artifacts());
        var candidates = proposal.tasks().stream()
                .filter(task -> task.status() == DebateTaskStatus.COMPLETED)
                .map(task -> candidate(
                        session,
                        task,
                        proposalArtifactsByTask.get(task.taskId().value())))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(value -> value.anonymousCandidateId().value()))
                .toList();
        if (!candidates.isEmpty()) {
            protocolStore.saveCandidates(candidates);
        }
        candidates = protocolStore.candidates(session.debateId());
        var participantRoleCount = candidates.stream()
                .map(DebateCandidate::logicalRoleKey)
                .distinct()
                .count();
        if (candidates.size() < configuration.minimumParticipantSeats()
                || participantRoleCount < configuration.minimumQuorumRoles()) {
            return driver.lowQuorumResult(
                    frozenExecution,
                    session,
                    decisionNode,
                    candidates,
                    Math.toIntExact(participantRoleCount));
        }

        var proposalArtifactsById = proposal.artifacts().stream().collect(Collectors.toUnmodifiableMap(
                artifact -> artifact.artifactId().value(),
                Function.identity()));
        var proposalViews = candidates.stream().collect(Collectors.toUnmodifiableMap(
                DebateCandidate::candidateId,
                candidate -> new DecisionPanelCandidateView(
                        candidate.anonymousCandidateId(),
                        proposalArtifactsById.get(candidate.proposalArtifactId().value()).content())));

        var critiqueCommands = critiqueCommands(
                frozenExecution,
                candidates,
                nodesById,
                proposalViews,
                configuration.critiqueAssignmentPolicy(),
                identityGuard,
                driver);
        var critique = phaseExecutor.execute(
                frozenExecution,
                session,
                GENERATION,
                DebatePhaseType.CRITIQUE,
                critiqueCommands);

        var critiqueArtifactsByTask = artifactsByTask(critique.artifacts());
        var revisionCommands = new ArrayList<SdbScaPhaseExecutor.TaskCommand>();
        for (var candidate : candidates) {
            var critiques = critique.tasks().stream()
                    .filter(task -> candidate.candidateId().value().equals(task.targetCandidateId()))
                    .filter(task -> task.status() == DebateTaskStatus.COMPLETED)
                    .map(task -> critiqueArtifactsByTask.get(task.taskId().value()))
                    .filter(Objects::nonNull)
                    .map(DebateArtifact::content)
                    .toList();
            var node = nodesById.get(candidate.originNodeId());
            revisionCommands.add(driver.revisionCommand(
                    frozenExecution,
                    node,
                    candidate.candidateId().value(),
                    proposalViews.get(candidate.candidateId()),
                    critiques,
                    identityGuard));
        }
        var revision = phaseExecutor.execute(
                frozenExecution,
                session,
                GENERATION,
                DebatePhaseType.REVISION,
                revisionCommands);
        var revisionArtifactsByTask = artifactsByTask(revision.artifacts());
        revision.tasks().stream()
                .filter(task -> task.status() == DebateTaskStatus.COMPLETED)
                .forEach(task -> {
                    var artifact = revisionArtifactsByTask.get(task.taskId().value());
                    if (artifact != null) {
                        protocolStore.attachRevision(
                                new io.omnnu.finbot.domain.debate.DebateCandidateId(
                                        task.targetCandidateId()),
                                artifact.artifactId());
                    }
                });
        candidates = protocolStore.candidates(session.debateId());
        var revisionArtifactsById = revision.artifacts().stream().collect(Collectors.toUnmodifiableMap(
                artifact -> artifact.artifactId().value(),
                Function.identity()));
        var revisedCandidates = candidates.stream()
                .filter(candidate -> candidate.revisionArtifactId() != null)
                .filter(candidate -> revisionArtifactsById.containsKey(
                        candidate.revisionArtifactId().value()))
                .sorted(Comparator.comparing(value -> value.anonymousCandidateId().value()))
                .toList();
        var revisedRoleCount = revisedCandidates.stream()
                .map(DebateCandidate::logicalRoleKey)
                .distinct()
                .count();
        var partial = proposal.partial() || critique.partial() || revision.partial();
        if (revisedCandidates.size() < configuration.minimumParticipantSeats()
                || revisedRoleCount < configuration.minimumQuorumRoles()) {
            return driver.lowQuorumResult(
                    frozenExecution,
                    session,
                    decisionNode,
                    revisedCandidates,
                    Math.toIntExact(revisedRoleCount));
        }

        var candidateViews = revisedCandidates.stream()
                .map(candidate -> new DecisionPanelCandidateView(
                        candidate.anonymousCandidateId(),
                        revisionArtifactsById.get(candidate.revisionArtifactId().value()).content()))
                .toList();
        var candidateAliases = candidateViews.stream()
                .map(DecisionPanelCandidateView::alias)
                .toList();
        var ballotCommands = new ArrayList<SdbScaPhaseExecutor.TaskCommand>();
        for (var candidate : revisedCandidates) {
            var node = nodesById.get(candidate.originNodeId());
            for (var orientation : BallotOrientation.values()) {
                ballotCommands.add(driver.ballotCommand(
                        frozenExecution,
                        node,
                        candidateViews,
                        orientation,
                        candidateAliases));
            }
        }
        var ballotPhase = phaseExecutor.execute(
                frozenExecution,
                session,
                GENERATION,
                DebatePhaseType.BALLOT,
                ballotCommands);
        partial = partial || ballotPhase.partial();
        var persistedBallots = parseCompleteBallotPairs(
                session,
                ballotPhase,
                nodesById,
                candidateAliases,
                driver);
        if (!persistedBallots.isEmpty()) {
            protocolStore.saveBallots(persistedBallots);
        }
        var ballots = protocolStore.ballots(session.debateId());
        var forward = ballots.stream()
                .map(ConsensusBallot::preference)
                .filter(ballot -> ballot.orientation() == BallotOrientation.FORWARD)
                .toList();
        var reversed = ballots.stream()
                .map(ConsensusBallot::preference)
                .filter(ballot -> ballot.orientation() == BallotOrientation.REVERSED)
                .toList();
        var detailed = consensusEngine.resolveDetailed(
                forward, reversed, configuration.minimumQuorumRoles());

        return driver.reduce(
                frozenExecution,
                session,
                decisionNode,
                revisedCandidates,
                revisionArtifactsById,
                detailed.outcome(),
                detailed,
                partial);
    }

    private <R> List<SdbScaPhaseExecutor.TaskCommand> critiqueCommands(
            WorkflowExecutionContext execution,
            List<DebateCandidate> candidates,
            Map<WorkflowNodeId, WorkflowNodeDefinition> nodesById,
            Map<io.omnnu.finbot.domain.debate.DebateCandidateId, DecisionPanelCandidateView> views,
            CritiqueAssignmentPolicy policy,
            SdbScaIdentityDisclosureGuard identityGuard,
            DecisionPanelProtocolDriver<R> driver) {
        var commands = new ArrayList<SdbScaPhaseExecutor.TaskCommand>();
        for (var actorIndex = 0; actorIndex < candidates.size(); actorIndex++) {
            var actor = candidates.get(actorIndex);
            var targets = critiqueTargets(candidates, actorIndex, policy);
            for (var target : targets) {
                var node = nodesById.get(actor.originNodeId());
                commands.add(driver.critiqueCommand(
                        execution,
                        node,
                        target.candidateId().value(),
                        views.get(target.candidateId()),
                        identityGuard));
            }
        }
        return List.copyOf(commands);
    }

    private static List<DebateCandidate> critiqueTargets(
            List<DebateCandidate> candidates,
            int actorIndex,
            CritiqueAssignmentPolicy policy) {
        if (policy == CritiqueAssignmentPolicy.FULL_MATRIX || candidates.size() <= 6) {
            var actor = candidates.get(actorIndex);
            return candidates.stream()
                    .filter(candidate -> !candidate.candidateId().equals(actor.candidateId()))
                    .toList();
        }
        var targets = new ArrayList<DebateCandidate>();
        var targetCount = Math.min(3, candidates.size() - 1);
        for (var offset = 1; offset <= targetCount; offset++) {
            targets.add(candidates.get((actorIndex + offset) % candidates.size()));
        }
        return List.copyOf(targets);
    }

    private <R> List<ConsensusBallot> parseCompleteBallotPairs(
            DebateSession session,
            SdbScaPhaseExecutor.PhaseResult phase,
            Map<WorkflowNodeId, WorkflowNodeDefinition> nodesById,
            List<AnonymousCandidateId> candidateAliases,
            DecisionPanelProtocolDriver<R> driver) {
        var artifactsByTask = artifactsByTask(phase.artifacts());
        var parsed = new ArrayList<ConsensusBallot>();
        for (var task : phase.tasks()) {
            if (task.status() != DebateTaskStatus.COMPLETED) {
                continue;
            }
            var artifact = artifactsByTask.get(task.taskId().value());
            var node = nodesById.get(task.actorNodeId());
            if (artifact == null || node == null) {
                continue;
            }
            var orientation = task.variant() == DebateTaskVariant.FORWARD
                    ? BallotOrientation.FORWARD
                    : BallotOrientation.REVERSED;
            var preference = driver.parseBallotPreference(
                    artifact.content(),
                    node,
                    orientation,
                    candidateAliases);
            parsed.add(new ConsensusBallot(
                    WorkflowExecutionIds.ballot(session.debateId(), task.actorNodeId(), task.variant()),
                    session.debateId(),
                    task.phaseId(),
                    task.actorNodeId(),
                    preference,
                    artifact.contentHash(),
                    artifact.sealedAt()));
        }
        var orientationsByActor = parsed.stream().collect(Collectors.groupingBy(
                ConsensusBallot::actorNodeId,
                Collectors.mapping(ballot -> ballot.preference().orientation(), Collectors.toSet())));
        return parsed.stream()
                .filter(ballot -> orientationsByActor.getOrDefault(
                                ballot.actorNodeId(),
                                java.util.Set.of())
                        .size() == BallotOrientation.values().length)
                .toList();
    }

    private static DebateCandidate candidate(
            DebateSession session,
            DebateTask task,
            DebateArtifact artifact) {
        if (artifact == null) {
            return null;
        }
        return new DebateCandidate(
                WorkflowExecutionIds.candidate(session.debateId(), task.actorNodeId()),
                session.debateId(),
                task.actorNodeId(),
                task.logicalRoleKey(),
                new AnonymousCandidateId(WorkflowExecutionIds.anonymousCandidateAlias(
                        session.debateId(), task.actorNodeId())),
                artifact.artifactId(),
                null,
                artifact.sealedAt());
    }

    private static Map<String, DebateArtifact> artifactsByTask(List<DebateArtifact> artifacts) {
        return artifacts.stream().collect(Collectors.toUnmodifiableMap(
                artifact -> artifact.taskId().value(),
                Function.identity()));
    }
}
