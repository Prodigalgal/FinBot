package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.application.workflow.dto.DebateSession;
import io.omnnu.finbot.application.workflow.dto.DecisionPanelCandidateView;
import io.omnnu.finbot.application.workflow.dto.PrincipalReviewResult;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.application.workflow.exception.SdbScaExecutionException;
import io.omnnu.finbot.application.workflow.port.in.PrincipalReviewUseCase;
import io.omnnu.finbot.application.workflow.port.out.DebateProtocolStore;
import io.omnnu.finbot.application.workflow.port.out.PrincipalReviewOutputParser;
import io.omnnu.finbot.application.workflow.port.out.SdbScaDocumentCodec;
import io.omnnu.finbot.application.workflow.port.out.WorkflowExecutionStore;
import io.omnnu.finbot.domain.consensus.AnonymousCandidateId;
import io.omnnu.finbot.domain.consensus.AnonymousPreferenceBallot;
import io.omnnu.finbot.domain.consensus.BallotOrientation;
import io.omnnu.finbot.domain.consensus.ConsensusDecision;
import io.omnnu.finbot.domain.consensus.ConsensusStatus;
import io.omnnu.finbot.domain.consensus.SchulzeDetailedResult;
import io.omnnu.finbot.domain.consensus.SchulzeOutcome;
import io.omnnu.finbot.domain.debate.DebateArtifact;
import io.omnnu.finbot.domain.debate.DebateCandidate;
import io.omnnu.finbot.domain.debate.DebateTaskVariant;
import io.omnnu.finbot.domain.debate.DecisionPanelKey;
import io.omnnu.finbot.domain.debate.DecisionPanelPurpose;
import io.omnnu.finbot.domain.debate.PrincipalReviewAction;
import io.omnnu.finbot.domain.debate.PrincipalReviewDecision;
import io.omnnu.finbot.domain.workflow.AgentClaim;
import io.omnnu.finbot.domain.workflow.AgentMessage;
import io.omnnu.finbot.domain.workflow.AgentMessageContent;
import io.omnnu.finbot.domain.workflow.AgentMessageStatus;
import io.omnnu.finbot.domain.workflow.AgentMessageType;
import io.omnnu.finbot.domain.workflow.DebateStatus;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PrincipalReviewService implements PrincipalReviewUseCase {
    private final WorkflowExecutionStore executionStore;
    private final DebateProtocolStore protocolStore;
    private final PrincipalReviewOutputParser outputParser;
    private final SdbScaDocumentCodec documentCodec;
    private final Clock clock;
    private final DecisionPanelEngine panelEngine;
    private final PrincipalReviewPromptComposer promptComposer;
    private final DecisionPanelSessionService panelSessions;

    public PrincipalReviewService(
            WorkflowExecutionStore executionStore,
            DebateProtocolStore protocolStore,
            PrincipalReviewOutputParser outputParser,
            SdbScaDocumentCodec documentCodec,
            Clock clock,
            DecisionPanelEngine panelEngine) {
        this.executionStore = Objects.requireNonNull(executionStore, "executionStore");
        this.protocolStore = Objects.requireNonNull(protocolStore, "protocolStore");
        this.outputParser = Objects.requireNonNull(outputParser, "outputParser");
        this.documentCodec = Objects.requireNonNull(documentCodec, "documentCodec");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.panelEngine = Objects.requireNonNull(panelEngine, "panelEngine");
        this.promptComposer = new PrincipalReviewPromptComposer();
        this.panelSessions = new DecisionPanelSessionService(executionStore, clock);
    }

    @Override
    public PrincipalReviewResult run(WorkflowExecutionContext execution) {
        Objects.requireNonNull(execution, "execution");
        var version = execution.definitionVersion();
        var decisionNode = version.decisionNode();
        if (decisionNode.nodeType() != WorkflowNodeType.SOCIAL_CHOICE || !decisionNode.enabled()) {
            throw new SdbScaExecutionException(
                    "PRINCIPAL_REVIEW_DECISION_NODE_INVALID",
                    "Principal review requires one enabled SOCIAL_CHOICE node",
                    false);
        }

        var researchDebateOpt = executionStore.findDebate(execution.runId(), DecisionPanelKey.RESEARCH);
        String researchConsensusSummary;
        if (researchDebateOpt.isPresent()) {
            var researchSession = researchDebateOpt.get();
            var researchDecisionOpt = protocolStore.decision(researchSession.debateId());
            if (researchDecisionOpt.isEmpty()
                    || researchDecisionOpt.get().outcome().status() != ConsensusStatus.SELECTED) {
                return failClosedDueToUpstreamResearch(
                        execution,
                        decisionNode,
                        researchDecisionOpt.map(d -> d.outcome().status().name()).orElse("MISSING"));
            }
            var researchMessage = executionStore.messages(researchSession.debateId()).stream()
                    .filter(m -> m.messageType() == AgentMessageType.CONSENSUS_RESULT)
                    .findFirst();
            researchConsensusSummary = researchMessage
                    .map(m -> m.content().summary() + "\n" + m.content().argument())
                    .orElseGet(() -> researchDecisionOpt.get().explanation());
        } else {
            researchConsensusSummary = execution.requestSummary();
        }

        var driver = createDriver(researchConsensusSummary);
        return panelEngine.execute(execution, decisionNode, version.topologicalNodes(), driver);
    }

    DecisionPanelProtocolDriver<PrincipalReviewResult> createDriver(String researchConsensusSummary) {
        return new PrincipalReviewDecisionPanelDriver(researchConsensusSummary);
    }

    private PrincipalReviewResult failClosedDueToUpstreamResearch(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition decisionNode,
            String upstreamStatus) {
        var session = panelSessions.ensure(
                execution,
                DecisionPanelKey.PRINCIPAL_REVIEW,
                DecisionPanelPurpose.PRINCIPAL_REVIEW,
                decisionNode.nodeId(),
                1);

        var existingMessage = executionStore.messages(session.debateId()).stream()
                .filter(m -> m.messageType() == AgentMessageType.CONSENSUS_RESULT)
                .findFirst();
        var existingDecision = protocolStore.decision(session.debateId());
        if (existingMessage.isPresent() && existingDecision.isPresent()) {
            return new PrincipalReviewResult(
                    session,
                    existingMessage.get(),
                    existingDecision.get(),
                    null,
                    false,
                    session.status() == DebateStatus.PARTIAL);
        }

        var explanation = "上游研究未达成严格共识（状态为 " + upstreamStatus + "），主审面板触发安全关闭（fail-closed），不进入投票，输出 NO_ACTION。";
        var outcome = SchulzeOutcome.unsuccessful(ConsensusStatus.NO_STRICT_WINNER, List.of(), 0);
        var rankingJson = documentCodec.encodeCandidateRanking(List.of());
        var decisionHash = WorkflowExecutionIds.sha256(
                outcome.status().name(),
                "",
                "{}",
                "{}",
                rankingJson,
                "");
        var decision = new ConsensusDecision(
                WorkflowExecutionIds.decision(session.debateId()),
                session.debateId(),
                outcome,
                null,
                "{}",
                "{}",
                rankingJson,
                null,
                explanation,
                decisionHash,
                clock.instant());
        protocolStore.saveDecision(decision);

        var content = new AgentMessageContent(
                "主审独立审计驳回: 上游研究未达成共识",
                explanation,
                null,
                List.of(),
                List.of("Upstream research consensus status: " + upstreamStatus),
                List.of("Fail-closed triggered due to absent or non-selected research consensus"),
                List.of());
        var message = new AgentMessage(
                WorkflowExecutionIds.message(session, decisionNode.nodeId(), 0),
                session.debateId(),
                execution.runId(),
                decisionNode.nodeId(),
                "主审独立审计",
                0,
                0,
                AgentMessageType.CONSENSUS_RESULT,
                AgentMessageStatus.COMPLETED,
                content,
                List.of(),
                clock.instant());
        executionStore.saveMessage(message);

        executionStore.transitionDebate(session.debateId(), session.version(), DebateStatus.COMPLETED, 1, clock.instant());

        return new PrincipalReviewResult(session, message, decision, null, false, false);
    }

    private final class PrincipalReviewDecisionPanelDriver
            implements DecisionPanelProtocolDriver<PrincipalReviewResult> {
        private final String researchConsensusSummary;

        PrincipalReviewDecisionPanelDriver(String researchConsensusSummary) {
            this.researchConsensusSummary = researchConsensusSummary;
        }

        @Override
        public DecisionPanelKey panelKey() {
            return DecisionPanelKey.PRINCIPAL_REVIEW;
        }

        @Override
        public DecisionPanelPurpose purpose() {
            return DecisionPanelPurpose.PRINCIPAL_REVIEW;
        }

        @Override
        public SdbScaPhaseExecutor.TaskCommand proposalCommand(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition node,
            SdbScaIdentityDisclosureGuard identityGuard) {
            return new SdbScaPhaseExecutor.TaskCommand(
                    node,
                    null,
                    DebateTaskVariant.PRIMARY,
                    promptComposer.proposal(execution, node, researchConsensusSummary),
                    output -> identityGuard.requireAnonymous(
                            outputParser.parseProposal(output).canonicalJson()));
        }

        @Override
        public SdbScaPhaseExecutor.TaskCommand critiqueCommand(
                WorkflowExecutionContext execution,
                WorkflowNodeDefinition node,
                String targetCandidateId,
                DecisionPanelCandidateView targetView,
                SdbScaIdentityDisclosureGuard identityGuard) {
            return new SdbScaPhaseExecutor.TaskCommand(
                    node,
                    targetCandidateId,
                    DebateTaskVariant.PRIMARY,
                    promptComposer.critique(execution, node, targetView),
                    output -> identityGuard.requireAnonymous(
                            outputParser.parseCritique(output)));
        }

        @Override
        public SdbScaPhaseExecutor.TaskCommand revisionCommand(
                WorkflowExecutionContext execution,
                WorkflowNodeDefinition node,
                String ownCandidateId,
                DecisionPanelCandidateView ownView,
                List<String> critiques,
                SdbScaIdentityDisclosureGuard identityGuard) {
            return new SdbScaPhaseExecutor.TaskCommand(
                    node,
                    ownCandidateId,
                    DebateTaskVariant.PRIMARY,
                    promptComposer.revision(execution, node, ownView, critiques),
                    output -> identityGuard.requireAnonymous(
                            outputParser.parseRevision(output).canonicalJson()));
        }

        @Override
        public SdbScaPhaseExecutor.TaskCommand ballotCommand(
                WorkflowExecutionContext execution,
                WorkflowNodeDefinition node,
                List<DecisionPanelCandidateView> candidateViews,
                BallotOrientation orientation,
                List<AnonymousCandidateId> candidateAliases) {
            var variant = orientation == BallotOrientation.FORWARD
                    ? DebateTaskVariant.FORWARD
                    : DebateTaskVariant.REVERSED;
            return new SdbScaPhaseExecutor.TaskCommand(
                    node,
                    null,
                    variant,
                    promptComposer.ballot(execution, node, candidateViews, orientation),
                    output -> outputParser.parseBallot(
                                    output,
                                    node.logicalRoleKey(),
                                    orientation,
                                    candidateAliases)
                            .canonicalJson());
        }

        @Override
        public AnonymousPreferenceBallot parseBallotPreference(
                String artifactContent,
                WorkflowNodeDefinition node,
                BallotOrientation orientation,
                List<AnonymousCandidateId> candidateAliases) {
            return outputParser.parseBallot(
                    artifactContent,
                    node.logicalRoleKey(),
                    orientation,
                    candidateAliases).preference();
        }

        @Override
        public PrincipalReviewResult reduce(
                WorkflowExecutionContext execution,
                DebateSession session,
                WorkflowNodeDefinition decisionNode,
                List<DebateCandidate> candidates,
                Map<String, DebateArtifact> revisionArtifactsById,
                SchulzeOutcome outcome,
                SchulzeDetailedResult detailed,
                boolean partial) {
            return completeResult(
                    execution,
                    session,
                    decisionNode,
                    candidates,
                    revisionArtifactsById,
                    outcome,
                    detailed,
                    partial);
        }

        @Override
        public PrincipalReviewResult lowQuorumResult(
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

        @Override
        public Optional<PrincipalReviewResult> recoverCompletedResult(
                DebateSession session,
                WorkflowNodeDefinition decisionNode) {
            var messageOpt = executionStore.messages(session.debateId()).stream()
                    .filter(message -> message.messageType() == AgentMessageType.CONSENSUS_RESULT)
                    .filter(message -> message.status() == AgentMessageStatus.COMPLETED)
                    .findFirst();
            var decisionOpt = protocolStore.decision(session.debateId());
            if (messageOpt.isPresent() && decisionOpt.isPresent()) {
                var decision = decisionOpt.get();
                PrincipalReviewDecision reviewDecision = null;
                boolean approved = false;
                if (decision.forecastJson() != null && !decision.forecastJson().isBlank()) {
                    try {
                        var parsed = outputParser.parseRevision(decision.forecastJson());
                        reviewDecision = parsed.decision();
                        approved = reviewDecision.action() != PrincipalReviewAction.REJECT;
                    } catch (Exception ignored) {
                    }
                }
                return Optional.of(new PrincipalReviewResult(
                        session,
                        messageOpt.get(),
                        decision,
                        reviewDecision,
                        approved,
                        session.status() == DebateStatus.PARTIAL));
            }
            return Optional.empty();
        }
    }

    private PrincipalReviewResult completeResult(
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

        PrincipalReviewDecision winningDecision = null;
        String winningJson = null;
        if (winner != null && winner.revisionArtifactId() != null) {
            var artifact = revisionArtifactsById.get(winner.revisionArtifactId().value());
            if (artifact != null) {
                var parsed = outputParser.parseRevision(artifact.content());
                winningDecision = parsed.decision();
                winningJson = parsed.canonicalJson();
            }
        }

        boolean approved = winningDecision != null && winningDecision.action() != PrincipalReviewAction.REJECT;
        var explanation = explanation(outcome, winningDecision);
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
                Objects.requireNonNullElse(winningJson, ""));

        var decision = new ConsensusDecision(
                WorkflowExecutionIds.decision(session.debateId()),
                session.debateId(),
                outcome,
                winner == null ? null : winner.candidateId(),
                pairwiseMatrixJson,
                strongestPathsJson,
                documentCodec.encodeCandidateRanking(ranking),
                winningJson,
                explanation,
                decisionHash,
                clock.instant());
        protocolStore.saveDecision(decision);

        var existingMessage = executionStore.messages(session.debateId()).stream()
                .filter(message -> message.messageId().equals(
                        WorkflowExecutionIds.message(session, decisionNode.nodeId(), 0)))
                .findFirst();
        if (existingMessage.isPresent()) {
            return new PrincipalReviewResult(
                    session,
                    existingMessage.orElseThrow(),
                    decision,
                    winningDecision,
                    approved,
                    partial);
        }

        var content = reviewMessageContent(outcome, winningDecision, explanation);
        var message = new AgentMessage(
                WorkflowExecutionIds.message(session, decisionNode.nodeId(), 0),
                session.debateId(),
                execution.runId(),
                decisionNode.nodeId(),
                "主审独立审计",
                0,
                candidates.size() + 1,
                AgentMessageType.CONSENSUS_RESULT,
                AgentMessageStatus.COMPLETED,
                content,
                List.of(),
                clock.instant());
        executionStore.saveMessage(message);

        return new PrincipalReviewResult(session, message, decision, winningDecision, approved, partial);
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

    private static String explanation(SchulzeOutcome outcome, PrincipalReviewDecision decision) {
        if (outcome.status() == ConsensusStatus.SELECTED) {
            if (decision != null) {
                return "主审审计决议: " + decision.action().name() + " - " + decision.summary();
            }
            return "主审通过对称社会选择产生唯一优胜方案。";
        }
        return switch (outcome.status()) {
            case TIED -> "主审候选票数并列且无法消解，根据单向安全原则不予放行（NO_ACTION）。";
            case LOW_QUORUM -> "主审法定有效席位不足，未能达到仲裁法定人数（Quorum不足）。";
            case ORDER_SENSITIVE -> "主审方案在正反向排序下结论敏感，未能通过双盲稳定性检验。";
            case NO_VALID_BALLOTS -> "主审选票未通过完整性验证。";
            case NO_STRICT_WINNER -> "主审投票未产生满足多数偏好的唯一严格胜者。";
            default -> "主审未形成可执行共识。";
        };
    }

    private static AgentMessageContent reviewMessageContent(
            SchulzeOutcome outcome,
            PrincipalReviewDecision decision,
            String explanation) {
        if (outcome.status() != ConsensusStatus.SELECTED || decision == null) {
            return new AgentMessageContent(
                    "主审未形成可执行共识",
                    explanation,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
        }
        var title = switch (decision.action()) {
            case CONFIRM -> "主审独立审计通过 (CONFIRM)";
            case TIGHTEN -> "主审独立审计单调收紧通过 (TIGHTEN)";
            case REJECT -> "主审独立审计驳回 (REJECT)";
        };
        var claims = decision.auditedClaims().stream()
                .map(c -> new AgentClaim(c, List.of()))
                .toList();
        return new AgentMessageContent(
                title,
                decision.auditRationale(),
                decision.tightenedConfidence(),
                claims,
                decision.counterexamples(),
                decision.riskWarnings(),
                List.of());
    }
}
