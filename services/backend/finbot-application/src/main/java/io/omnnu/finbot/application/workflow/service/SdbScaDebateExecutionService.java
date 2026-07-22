package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.application.ai.service.AiExecutionPolicyExecutor;
import io.omnnu.finbot.application.workflow.dto.DebateSession;
import io.omnnu.finbot.application.workflow.dto.SdbScaDebateResult;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.application.workflow.exception.SdbScaExecutionException;
import io.omnnu.finbot.application.workflow.port.in.SdbScaDebateRunner;
import io.omnnu.finbot.application.workflow.port.out.DebateProtocolStore;
import io.omnnu.finbot.application.workflow.port.out.SdbScaDocumentCodec;
import io.omnnu.finbot.application.workflow.port.out.SdbScaOutputParser;
import io.omnnu.finbot.application.workflow.port.out.WorkflowExecutionStore;
import io.omnnu.finbot.domain.consensus.AnonymousCandidateId;
import io.omnnu.finbot.domain.consensus.BallotOrientation;
import io.omnnu.finbot.domain.consensus.ConsensusBallot;
import io.omnnu.finbot.domain.consensus.ConsensusDecision;
import io.omnnu.finbot.domain.consensus.ConsensusStatus;
import io.omnnu.finbot.domain.consensus.RoleForecastSignal;
import io.omnnu.finbot.domain.consensus.RoleNormalizedForecastAggregator;
import io.omnnu.finbot.domain.consensus.SchulzeConsensusEngine;
import io.omnnu.finbot.domain.consensus.SchulzeDetailedResult;
import io.omnnu.finbot.domain.consensus.SchulzeOutcome;
import io.omnnu.finbot.domain.debate.CritiqueAssignmentPolicy;
import io.omnnu.finbot.domain.debate.DebateArtifact;
import io.omnnu.finbot.domain.debate.DebateCandidate;
import io.omnnu.finbot.domain.debate.DebatePhaseType;
import io.omnnu.finbot.domain.debate.DebateTask;
import io.omnnu.finbot.domain.debate.DebateTaskStatus;
import io.omnnu.finbot.domain.debate.DebateTaskVariant;
import io.omnnu.finbot.domain.research.ForecastSignal;
import io.omnnu.finbot.domain.workflow.AgentMessage;
import io.omnnu.finbot.domain.workflow.AgentMessageContent;
import io.omnnu.finbot.domain.workflow.AgentMessageStatus;
import io.omnnu.finbot.domain.workflow.AgentMessageType;
import io.omnnu.finbot.domain.workflow.DebateStatus;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class SdbScaDebateExecutionService implements SdbScaDebateRunner {
    private static final int GENERATION = 1;

    private final WorkflowExecutionStore executionStore;
    private final DebateProtocolStore protocolStore;
    private final SdbScaOutputParser outputParser;
    private final SdbScaDocumentCodec documentCodec;
    private final Clock clock;
    private final SdbScaPhaseExecutor phaseExecutor;
    private final SdbScaPromptComposer promptComposer = new SdbScaPromptComposer();
    private final WorkflowConditionEvaluator conditionEvaluator = new WorkflowConditionEvaluator();
    private final SchulzeConsensusEngine consensusEngine = new SchulzeConsensusEngine();
    private final RoleNormalizedForecastAggregator forecastAggregator =
            new RoleNormalizedForecastAggregator();

    public SdbScaDebateExecutionService(
            WorkflowExecutionStore executionStore,
            DebateProtocolStore protocolStore,
            AiExecutionPolicyExecutor aiExecution,
            SdbScaOutputParser outputParser,
            SdbScaDocumentCodec documentCodec,
            Clock clock,
            Executor executor) {
        this.executionStore = Objects.requireNonNull(executionStore, "executionStore");
        this.protocolStore = Objects.requireNonNull(protocolStore, "protocolStore");
        this.outputParser = Objects.requireNonNull(outputParser, "outputParser");
        this.documentCodec = Objects.requireNonNull(documentCodec, "documentCodec");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.phaseExecutor = new SdbScaPhaseExecutor(
                protocolStore,
                Objects.requireNonNull(aiExecution, "aiExecution"),
                clock,
                Objects.requireNonNull(executor, "executor"));
    }

    @Override
    public SdbScaDebateResult run(WorkflowExecutionContext execution) {
        Objects.requireNonNull(execution, "execution");
        var version = execution.definitionVersion();
        var decisionNode = version.decisionNode();
        if (decisionNode.nodeType() != WorkflowNodeType.SOCIAL_CHOICE || !decisionNode.enabled()) {
            throw new SdbScaExecutionException(
                    "SDB_DECISION_NODE_INVALID",
                    "SDB-SCA requires one enabled SOCIAL_CHOICE node",
                    false);
        }
        var session = ensureDebate(execution, decisionNode);
        var configuredParticipants = version.topologicalNodes().stream()
                .filter(WorkflowNodeDefinition::enabled)
                .filter(node -> node.nodeType() == WorkflowNodeType.AGENT
                        || node.nodeType() == WorkflowNodeType.AGGREGATOR)
                .filter(node -> conditionEvaluator.isActive(execution, node, 1, List.of()))
                .sorted(Comparator.comparing(node -> node.nodeId().value()))
                .toList();
        var configuration = version.debateProtocolConfiguration();
        var identityGuard = new SdbScaIdentityDisclosureGuard(configuredParticipants);
        if (configuredParticipants.size() < configuration.minimumParticipantSeats()) {
            return lowQuorumResult(execution, session, decisionNode, List.of(), 0);
        }

        var proposal = phaseExecutor.execute(
                execution,
                session,
                GENERATION,
                DebatePhaseType.PROPOSAL,
                configuredParticipants.stream()
                        .map(node -> command(
                                node,
                                null,
                                DebateTaskVariant.PRIMARY,
                                promptComposer.proposal(execution, node),
                                output -> identityGuard.requireAnonymous(
                                        outputParser.parseProposal(output).canonicalJson())))
                        .toList());
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
            return lowQuorumResult(
                    execution,
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
                candidate -> new SdbScaPromptComposer.CandidateView(
                        candidate.anonymousCandidateId(),
                        proposalArtifactsById.get(candidate.proposalArtifactId().value()).content())));
        var critiqueCommands = critiqueCommands(
                execution,
                candidates,
                nodesById,
                proposalViews,
                configuration.critiqueAssignmentPolicy(),
                identityGuard);
        var critique = phaseExecutor.execute(
                execution,
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
            revisionCommands.add(command(
                    node,
                    candidate.candidateId().value(),
                    DebateTaskVariant.PRIMARY,
                    promptComposer.revision(execution, node, proposalViews.get(candidate.candidateId()), critiques),
                    output -> identityGuard.requireAnonymous(
                            outputParser.parseRevision(output).canonicalJson())));
        }
        var revision = phaseExecutor.execute(
                execution,
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
            return lowQuorumResult(
                    execution,
                    session,
                    decisionNode,
                    revisedCandidates,
                    Math.toIntExact(revisedRoleCount));
        }

        var candidateViews = revisedCandidates.stream()
                .map(candidate -> new SdbScaPromptComposer.CandidateView(
                        candidate.anonymousCandidateId(),
                        revisionArtifactsById.get(candidate.revisionArtifactId().value()).content()))
                .toList();
        var candidateAliases = candidateViews.stream()
                .map(SdbScaPromptComposer.CandidateView::alias)
                .toList();
        var ballotCommands = new ArrayList<SdbScaPhaseExecutor.TaskCommand>();
        for (var candidate : revisedCandidates) {
            var node = nodesById.get(candidate.originNodeId());
            for (var orientation : BallotOrientation.values()) {
                var variant = orientation == BallotOrientation.FORWARD
                        ? DebateTaskVariant.FORWARD
                        : DebateTaskVariant.REVERSED;
                ballotCommands.add(command(
                        node,
                        null,
                        variant,
                        promptComposer.ballot(execution, node, candidateViews, orientation),
                        output -> outputParser.parseBallot(
                                        output,
                                        candidate.logicalRoleKey(),
                                        orientation,
                                        candidateAliases)
                                .canonicalJson()));
            }
        }
        var ballotPhase = phaseExecutor.execute(
                execution,
                session,
                GENERATION,
                DebatePhaseType.BALLOT,
                ballotCommands);
        partial = partial || ballotPhase.partial();
        var persistedBallots = parseCompleteBallotPairs(
                session,
                ballotPhase,
                nodesById,
                candidateAliases);
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
        return completeResult(
                execution,
                session,
                decisionNode,
                revisedCandidates,
                revisionArtifactsById,
                detailed.outcome(),
                detailed,
                partial);
    }

    private SdbScaDebateResult lowQuorumResult(
            WorkflowExecutionContext execution,
            DebateSession session,
            WorkflowNodeDefinition decisionNode,
            List<DebateCandidate> candidates,
            int roleCount) {
        var outcome = SchulzeOutcome.unsuccessful(
                ConsensusStatus.LOW_QUORUM,
                candidates.stream().map(DebateCandidate::anonymousCandidateId).toList(),
                roleCount);
        return completeResult(
                execution,
                session,
                decisionNode,
                candidates,
                Map.of(),
                outcome,
                null,
                true);
    }

    private SdbScaDebateResult completeResult(
            WorkflowExecutionContext execution,
            DebateSession session,
            WorkflowNodeDefinition decisionNode,
            List<DebateCandidate> candidates,
            Map<String, DebateArtifact> revisionArtifactsById,
            SchulzeOutcome outcome,
            SchulzeDetailedResult detailed,
            boolean partial) {
        var ranking = ranking(outcome, candidates);
        var winner = outcome.selected().flatMap(alias -> candidates.stream()
                        .filter(candidate -> candidate.anonymousCandidateId().equals(alias))
                        .findFirst())
                .orElse(null);
        var roleForecasts = new ArrayList<RoleForecastSignal>();
        AgentMessageContent winnerContent = null;
        for (var candidate : candidates) {
            if (candidate.revisionArtifactId() == null) {
                continue;
            }
            var artifact = revisionArtifactsById.get(candidate.revisionArtifactId().value());
            if (artifact == null) {
                continue;
            }
            var content = outputParser.parseRevision(artifact.content()).messageContent();
            if (content.forecast() != null) {
                roleForecasts.add(new RoleForecastSignal(candidate.logicalRoleKey(), content.forecast()));
            }
            if (winner != null && winner.candidateId().equals(candidate.candidateId())) {
                winnerContent = content;
            }
        }
        var forecast = aggregateForecast(execution, outcome, roleForecasts);
        var forecastJson = documentCodec.encodeForecast(forecast);
        var explanation = explanation(outcome);
        var pairwiseMatrixJson = detailed == null
                ? "{}"
                : documentCodec.encodePairwiseMatrix(detailed);
        var strongestPathsJson = detailed == null
                ? "{}"
                : documentCodec.encodeStrongestPaths(detailed);
        var decisionHash = WorkflowExecutionIds.sha256(
                outcome.status().name(),
                outcome.selected().map(AnonymousCandidateId::value).orElse(""),
                pairwiseMatrixJson,
                strongestPathsJson,
                documentCodec.encodeCandidateRanking(ranking),
                Objects.requireNonNullElse(forecastJson, ""));
        var decision = new ConsensusDecision(
                WorkflowExecutionIds.decision(session.debateId()),
                session.debateId(),
                outcome,
                winner == null ? null : winner.candidateId(),
                pairwiseMatrixJson,
                strongestPathsJson,
                documentCodec.encodeCandidateRanking(ranking),
                forecastJson,
                explanation,
                decisionHash,
                clock.instant());
        protocolStore.saveDecision(decision);

        var existingMessage = executionStore.messages(session.debateId()).stream()
                .filter(message -> message.messageId().equals(
                        WorkflowExecutionIds.message(execution.runId(), decisionNode.nodeId(), 0)))
                .findFirst();
        if (existingMessage.isPresent()) {
            return new SdbScaDebateResult(session, existingMessage.orElseThrow(), partial);
        }
        var content = consensusMessageContent(outcome, winnerContent, forecast, explanation);
        var message = new AgentMessage(
                WorkflowExecutionIds.message(execution.runId(), decisionNode.nodeId(), 0),
                session.debateId(),
                execution.runId(),
                decisionNode.nodeId(),
                "对称社会选择",
                0,
                candidates.size() + 1,
                AgentMessageType.CONSENSUS_RESULT,
                AgentMessageStatus.COMPLETED,
                content,
                List.of(),
                clock.instant());
        executionStore.saveMessage(message);
        return new SdbScaDebateResult(session, message, partial);
    }

    private ForecastSignal aggregateForecast(
            WorkflowExecutionContext execution,
            SchulzeOutcome outcome,
            List<RoleForecastSignal> roleForecasts) {
        if (execution.marketScope() == null) {
            return null;
        }
        return forecastAggregator.resolve(
                roleForecasts,
                execution.marketScope().marketReferencePrice(),
                outcome.status() == ConsensusStatus.SELECTED);
    }

    private static AgentMessageContent consensusMessageContent(
            SchulzeOutcome outcome,
            AgentMessageContent winnerContent,
            ForecastSignal forecast,
            String explanation) {
        if (outcome.status() != ConsensusStatus.SELECTED || winnerContent == null) {
            return new AgentMessageContent(
                    "SDB-SCA 未形成可执行共识",
                    explanation,
                    forecast == null ? null : forecast.confidence(),
                    List.of(),
                    forecast == null ? List.of() : forecast.evidenceReferences(),
                    List.of(explanation),
                    List.of(),
                    forecast);
        }
        return new AgentMessageContent(
                "SDB-SCA 已通过对称社会选择形成共识",
                winnerContent.argument(),
                forecast == null ? winnerContent.confidence() : forecast.confidence(),
                winnerContent.claims(),
                winnerContent.evidenceReferences(),
                winnerContent.challenges(),
                winnerContent.revisionNotes(),
                forecast);
    }

    private List<ConsensusBallot> parseCompleteBallotPairs(
            DebateSession session,
            SdbScaPhaseExecutor.PhaseResult phase,
            Map<WorkflowNodeId, WorkflowNodeDefinition> nodesById,
            List<AnonymousCandidateId> candidateAliases) {
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
            var preference = outputParser.parseBallot(
                    artifact.content(),
                    node.logicalRoleKey(),
                    orientation,
                    candidateAliases).preference();
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

    private List<SdbScaPhaseExecutor.TaskCommand> critiqueCommands(
            WorkflowExecutionContext execution,
            List<DebateCandidate> candidates,
            Map<WorkflowNodeId, WorkflowNodeDefinition> nodesById,
            Map<io.omnnu.finbot.domain.debate.DebateCandidateId, SdbScaPromptComposer.CandidateView> views,
            CritiqueAssignmentPolicy policy,
            SdbScaIdentityDisclosureGuard identityGuard) {
        var commands = new ArrayList<SdbScaPhaseExecutor.TaskCommand>();
        for (var actorIndex = 0; actorIndex < candidates.size(); actorIndex++) {
            var actor = candidates.get(actorIndex);
            var targets = critiqueTargets(candidates, actorIndex, policy);
            for (var target : targets) {
                var node = nodesById.get(actor.originNodeId());
                commands.add(command(
                        node,
                        target.candidateId().value(),
                        DebateTaskVariant.PRIMARY,
                        promptComposer.critique(execution, node, views.get(target.candidateId())),
                        output -> identityGuard.requireAnonymous(
                                outputParser.parseCritique(output).canonicalJson())));
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

    private DebateSession ensureDebate(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition decisionNode) {
        var existing = executionStore.findDebate(execution.runId());
        if (existing.isPresent()) {
            var session = existing.orElseThrow();
            if (!session.decisionNodeId().equals(decisionNode.nodeId())) {
                throw new SdbScaExecutionException(
                        "SDB_DECISION_NODE_MISMATCH",
                        "Persisted debate decision node does not match the immutable workflow version",
                        false);
            }
            return session;
        }
        var proposed = new DebateSession(
                WorkflowExecutionIds.debate(execution.runId()),
                execution.runId(),
                DebateStatus.RUNNING,
                1,
                0,
                decisionNode.nodeId(),
                clock.instant(),
                null);
        executionStore.startDebate(proposed);
        return executionStore.findDebate(execution.runId()).orElse(proposed);
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

    private static SdbScaPhaseExecutor.TaskCommand command(
            WorkflowNodeDefinition node,
            String targetCandidateId,
            DebateTaskVariant variant,
            String prompt,
            Function<String, String> parser) {
        return new SdbScaPhaseExecutor.TaskCommand(
                node, targetCandidateId, variant, prompt, parser);
    }

    private static Map<String, DebateArtifact> artifactsByTask(List<DebateArtifact> artifacts) {
        return artifacts.stream().collect(Collectors.toUnmodifiableMap(
                artifact -> artifact.taskId().value(),
                Function.identity()));
    }

    private static List<AnonymousCandidateId> ranking(
            SchulzeOutcome outcome,
            List<DebateCandidate> candidates) {
        var ordered = new LinkedHashSet<AnonymousCandidateId>();
        outcome.selected().ifPresent(ordered::add);
        ordered.addAll(outcome.undefeatedCandidates());
        candidates.stream()
                .map(DebateCandidate::anonymousCandidateId)
                .sorted(Comparator.comparing(AnonymousCandidateId::value))
                .forEach(ordered::add);
        return List.copyOf(ordered);
    }

    private static String explanation(SchulzeOutcome outcome) {
        return switch (outcome.status()) {
            case SELECTED -> "正序与逆序匿名选票得到同一唯一严格 Schulze 胜者。";
            case TIED -> "Schulze 最强路径存在多个不败候选，结果并列。";
            case LOW_QUORUM -> "有效逻辑角色数低于工作流配置的法定人数。";
            case ORDER_SENSITIVE -> "正序与逆序展示产生不同胜者，结果具有顺序敏感性。";
            case NO_VALID_BALLOTS -> "没有同时具备正序与逆序的有效完整选票。";
            case NO_STRICT_WINNER -> "不存在严格击败所有其他候选的唯一胜者。";
        };
    }
}
