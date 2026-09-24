package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.application.trading.dto.TradeDecisionDraft;
import io.omnnu.finbot.application.workflow.dto.DebateSession;
import io.omnnu.finbot.application.workflow.dto.DecisionPanelCandidateView;
import io.omnnu.finbot.application.workflow.dto.ExecutionPanelResult;
import io.omnnu.finbot.application.workflow.dto.PrincipalReviewResult;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.application.workflow.exception.SdbScaExecutionException;
import io.omnnu.finbot.application.workflow.port.in.ExecutionPanelUseCase;
import io.omnnu.finbot.application.workflow.port.out.DebateProtocolStore;
import io.omnnu.finbot.application.workflow.port.out.ExecutionPanelOutputParser;
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
import io.omnnu.finbot.domain.market.Price;
import io.omnnu.finbot.domain.trading.Confidence;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import io.omnnu.finbot.domain.workflow.AgentMessage;
import io.omnnu.finbot.domain.workflow.AgentMessageContent;
import io.omnnu.finbot.domain.workflow.AgentMessageStatus;
import io.omnnu.finbot.domain.workflow.AgentMessageType;
import io.omnnu.finbot.domain.workflow.DebateStatus;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public final class ExecutionPanelService implements ExecutionPanelUseCase {
    private static final MathContext CALCULATION = new MathContext(24, RoundingMode.HALF_EVEN);

    private final WorkflowExecutionStore executionStore;
    private final DebateProtocolStore protocolStore;
    private final ExecutionPanelOutputParser outputParser;
    private final SdbScaDocumentCodec documentCodec;
    private final Clock clock;
    private final Executor executor;
    private final DecisionPanelEngine panelEngine;
    private final ExecutionPromptComposer promptComposer;
    private final DecisionPanelSessionService panelSessions;

    public ExecutionPanelService(
            WorkflowExecutionStore executionStore,
            DebateProtocolStore protocolStore,
            ExecutionPanelOutputParser outputParser,
            SdbScaDocumentCodec documentCodec,
            Clock clock,
            Executor executor,
            DecisionPanelEngine panelEngine) {
        this.executionStore = Objects.requireNonNull(executionStore, "executionStore");
        this.protocolStore = Objects.requireNonNull(protocolStore, "protocolStore");
        this.outputParser = Objects.requireNonNull(outputParser, "outputParser");
        this.documentCodec = Objects.requireNonNull(documentCodec, "documentCodec");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.panelEngine = Objects.requireNonNull(panelEngine, "panelEngine");
        this.promptComposer = new ExecutionPromptComposer();
        this.panelSessions = new DecisionPanelSessionService(executionStore, clock);
    }

    @Override
    public CompletionStage<ExecutionPanelResult> execute(
            WorkflowRunId workflowRunId,
            PrincipalReviewResult principalReview) {
        Objects.requireNonNull(workflowRunId, "workflowRunId");
        return CompletableFuture.supplyAsync(() -> {
            var execution = executionStore.load(workflowRunId)
                    .orElseThrow(() -> new IllegalArgumentException("Workflow run does not exist: " + workflowRunId.value()));
            if (execution.status() != WorkflowRunStatus.RUNNING
                    && execution.status() != WorkflowRunStatus.COMPLETED
                    && execution.status() != WorkflowRunStatus.PARTIAL) {
                throw new IllegalStateException("Workflow is not in an executable state: " + execution.status());
            }
            return run(execution, principalReview);
        }, executor);
    }

    @Override
    public ExecutionPanelResult run(
            WorkflowExecutionContext execution,
            PrincipalReviewResult principalReview) {
        Objects.requireNonNull(execution, "execution");
        var version = execution.definitionVersion();
        var decisionNode = version.decisionNode();
        if (decisionNode.nodeType() != WorkflowNodeType.SOCIAL_CHOICE || !decisionNode.enabled()) {
            throw new SdbScaExecutionException(
                    "EXECUTION_DECISION_NODE_INVALID",
                    "Execution panel requires one enabled SOCIAL_CHOICE node",
                    false);
        }

        // 校验主审审计结果
        if (principalReview == null || !principalReview.approved()
                || principalReview.reviewDecision() == null
                || principalReview.reviewDecision().action() == PrincipalReviewAction.REJECT) {
            return failClosedDueToPrincipalReview(
                    execution,
                    decisionNode,
                    principalReview == null ? "MISSING" : principalReview.reviewDecision() == null ? "NO_DECISION" : "REJECTED");
        }

        var driver = new ExecutionDecisionPanelDriver(principalReview);
        return panelEngine.execute(execution, decisionNode, version.topologicalNodes(), driver);
    }

    private ExecutionPanelResult failClosedDueToPrincipalReview(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition decisionNode,
            String reason) {
        var session = panelSessions.ensure(
                execution,
                DecisionPanelKey.EXECUTION,
                DecisionPanelPurpose.EXECUTION,
                decisionNode.nodeId(),
                1);

        var existingMessage = executionStore.messages(session.debateId()).stream()
                .filter(m -> m.messageType() == AgentMessageType.CONSENSUS_RESULT)
                .findFirst();
        var existingDecision = protocolStore.decision(session.debateId());
        if (existingMessage.isPresent() && existingDecision.isPresent()) {
            return new ExecutionPanelResult(
                    session,
                    existingMessage.get(),
                    existingDecision.get(),
                    null,
                    false,
                    session.status() == DebateStatus.PARTIAL);
        }

        var explanation = "主审独立审计未通过（状态为 " + reason + "），执行面板触发严格安全关闭（fail-closed），不执行下单。";
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
                "执行面板已安全关闭",
                explanation,
                null,
                List.of(),
                List.of("Principal review status: " + reason),
                List.of(),
                List.of());

        var message = new AgentMessage(
                WorkflowExecutionIds.message(session, decisionNode.nodeId(), 0),
                session.debateId(),
                execution.runId(),
                decisionNode.nodeId(),
                "执行共识面板",
                0,
                0,
                AgentMessageType.CONSENSUS_RESULT,
                AgentMessageStatus.COMPLETED,
                content,
                List.of(),
                clock.instant());
        executionStore.saveMessage(message);
        executionStore.transitionDebate(session.debateId(), session.version(), DebateStatus.COMPLETED, 1, clock.instant());

        return new ExecutionPanelResult(session, message, decision, null, false, false);
    }

    final class ExecutionDecisionPanelDriver implements DecisionPanelProtocolDriver<ExecutionPanelResult> {
        private final PrincipalReviewResult principalReview;

        ExecutionDecisionPanelDriver(PrincipalReviewResult principalReview) {
            this.principalReview = Objects.requireNonNull(principalReview, "principalReview");
        }

        @Override
        public DecisionPanelKey panelKey() {
            return DecisionPanelKey.EXECUTION;
        }

        @Override
        public DecisionPanelPurpose purpose() {
            return DecisionPanelPurpose.EXECUTION;
        }

        @Override
        public SdbScaPhaseExecutor.TaskCommand proposalCommand(
                WorkflowExecutionContext execution,
                WorkflowNodeDefinition node,
                SdbScaIdentityDisclosureGuard identityGuard) {
            var auditSummary = principalReview.message().content().summary() + "\n"
                    + principalReview.message().content().argument();
            return new SdbScaPhaseExecutor.TaskCommand(
                    node,
                    null,
                    DebateTaskVariant.PRIMARY,
                    promptComposer.proposal(execution, node, auditSummary),
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
        public ExecutionPanelResult reduce(
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
        public ExecutionPanelResult lowQuorumResult(
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
        public Optional<ExecutionPanelResult> recoverCompletedResult(
                DebateSession session,
                WorkflowNodeDefinition decisionNode) {
            var messageOpt = executionStore.messages(session.debateId()).stream()
                    .filter(message -> message.messageType() == AgentMessageType.CONSENSUS_RESULT)
                    .filter(message -> message.status() == AgentMessageStatus.COMPLETED)
                    .findFirst();
            var decisionOpt = protocolStore.decision(session.debateId());
            if (messageOpt.isPresent() && decisionOpt.isPresent()) {
                var decision = decisionOpt.get();
                TradeDecisionDraft draft = null;
                boolean approved = false;
                if (decision.forecastJson() != null && !decision.forecastJson().isBlank()) {
                    try {
                        var parsed = outputParser.parseRevision(decision.forecastJson());
                        draft = applyPrincipalReviewTightening(parsed.draft(), principalReview);
                        approved = decision.outcome().status() == ConsensusStatus.SELECTED && draft != null;
                    } catch (Exception ignored) {
                    }
                }
                return Optional.of(new ExecutionPanelResult(
                        session,
                        messageOpt.get(),
                        decision,
                        draft,
                        approved,
                        session.status() == DebateStatus.PARTIAL));
            }
            return Optional.empty();
        }

        private ExecutionPanelResult completeResult(
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

            TradeDecisionDraft winningDraft = null;
            String winningJson = null;
            if (winner != null && winner.revisionArtifactId() != null) {
                var artifact = revisionArtifactsById.get(winner.revisionArtifactId().value());
                if (artifact != null) {
                    var parsed = outputParser.parseRevision(artifact.content());
                    winningDraft = applyPrincipalReviewTightening(parsed.draft(), principalReview);
                    winningJson = parsed.canonicalJson();
                }
            }

            boolean approved = outcome.status() == ConsensusStatus.SELECTED && winningDraft != null;
            var explanation = outcome.status() == ConsensusStatus.SELECTED && winningDraft != null
                    ? "SDB-SCA 执行面板通过舒尔茨法则达成严格共识，已选中最优执行方案。"
                    : "SDB-SCA 执行面板未形成唯一严格胜出方案（状态为 " + outcome.status() + "）。";

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
                return new ExecutionPanelResult(
                        session,
                        existingMessage.orElseThrow(),
                        decision,
                        winningDraft,
                        approved,
                        partial);
            }

            var content = new AgentMessageContent(
                    approved ? "执行决策共识达成" : "执行决策共识未达成",
                    explanation,
                    winningDraft != null ? winningDraft.confidence().value() : null,
                    List.of(),
                    winningDraft != null ? winningDraft.evidenceReferences() : List.of(),
                    List.of(),
                    winningDraft != null ? winningDraft.rationale() : List.of());

            var message = new AgentMessage(
                    WorkflowExecutionIds.message(session, decisionNode.nodeId(), 0),
                    session.debateId(),
                    execution.runId(),
                    decisionNode.nodeId(),
                    "执行共识面板",
                    0,
                    candidates.size() + 1,
                    AgentMessageType.CONSENSUS_RESULT,
                    AgentMessageStatus.COMPLETED,
                    content,
                    List.of(),
                    clock.instant());
            executionStore.saveMessage(message);

            return new ExecutionPanelResult(session, message, decision, winningDraft, approved, partial);
        }

        TradeDecisionDraft applyPrincipalReviewTightening(
                TradeDecisionDraft draft,
                PrincipalReviewResult review) {
            if (draft == null || review == null || review.reviewDecision() == null
                    || review.reviewDecision().action() != PrincipalReviewAction.TIGHTEN) {
                return draft;
            }
            var tightening = review.reviewDecision();
            var rationale = new ArrayList<>(draft.rationale());
            var confidenceVal = draft.confidence().value();

            // 收紧置信度
            if (tightening.tightenedConfidence() != null
                    && confidenceVal.compareTo(tightening.tightenedConfidence()) > 0) {
                confidenceVal = tightening.tightenedConfidence();
                rationale.add("[主审审计强制收紧置信度至: " + tightening.tightenedConfidence() + "]");
            }

            // 收紧止损距离
            var invalidationPrice = draft.invalidationPrice();
            if (tightening.tightenedStopDistance() != null
                    && draft.action() instanceof DirectionalAction action
                    && draft.entryReference() != null
                    && invalidationPrice != null) {
                var entry = draft.entryReference().value();
                var currentStopDist = entry.subtract(invalidationPrice.value())
                        .abs()
                        .divide(entry, CALCULATION);
                if (currentStopDist.compareTo(tightening.tightenedStopDistance()) > 0) {
                    var allowedDistance = tightening.tightenedStopDistance();
                    var newStopVal = action == DirectionalAction.BUY
                            ? entry.multiply(BigDecimal.ONE.subtract(allowedDistance), CALCULATION)
                            : entry.multiply(BigDecimal.ONE.add(allowedDistance), CALCULATION);
                    invalidationPrice = new Price(newStopVal);
                    rationale.add("[主审审计强制收紧止损距离至: " + allowedDistance + "]");
                }
            }

            return new TradeDecisionDraft(
                    draft.action(),
                    draft.symbol(),
                    new Confidence(confidenceVal),
                    draft.entryReference(),
                    draft.targetPrice(),
                    invalidationPrice,
                    rationale,
                    draft.evidenceReferences());
        }

        private static List<AnonymousCandidateId> ranking(
                SchulzeOutcome outcome,
                List<DebateCandidate> candidates) {
            var ordered = new LinkedHashSet<AnonymousCandidateId>();
            outcome.selected().ifPresent(ordered::add);
            ordered.addAll(outcome.undefeatedCandidates());
            candidates.stream()
                    .map(DebateCandidate::anonymousCandidateId)
                    .sorted(java.util.Comparator.comparing(AnonymousCandidateId::value))
                    .forEach(ordered::add);
            return List.copyOf(ordered);
        }
    }
}
