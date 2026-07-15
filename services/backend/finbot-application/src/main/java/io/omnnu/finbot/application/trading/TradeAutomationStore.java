package io.omnnu.finbot.application.trading;

import io.omnnu.finbot.domain.risk.RiskInstrumentSpec;
import io.omnnu.finbot.domain.risk.RiskPolicy;
import io.omnnu.finbot.domain.risk.ProjectionInstrumentSpec;
import io.omnnu.finbot.domain.trading.ApprovedTradeIntent;
import io.omnnu.finbot.domain.trading.TradeDecision;
import io.omnnu.finbot.domain.trading.TradeProposal;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import io.omnnu.finbot.application.exchange.PaperOrderExecutionResult;

public interface TradeAutomationStore {
    Optional<TradeAutomationResult> findTerminal(WorkflowRunId workflowRunId);

    boolean start(String automationRunId, WorkflowRunId workflowRunId, Instant startedAt);

    List<TradeExecutionAiStageConfig> executionAiStages();

    RiskPolicy activeRiskPolicy();

    List<RiskInstrumentSpec> executionCandidates(String normalizedSymbol);

    List<ProjectionInstrumentSpec> projectionCandidates(String normalizedSymbol);

    void saveExecutionAiReview(StoredExecutionAiReview review);

    void saveExecutionAiFailure(
            String reviewId,
            String automationRunId,
            WorkflowRunId workflowRunId,
            TradeExecutionAiStage stage,
            String errorCode,
            String safeMessage,
            Instant createdAt);

    void saveDecision(WorkflowRunId workflowRunId, TradeDecision decision);

    void saveProposal(TradeProposal proposal);

    void saveRiskAssessment(StoredRiskAssessment assessment);

    void saveEstimatedTradeProjection(StoredEstimatedTradeProjection projection);

    void saveApprovedIntentAndOrder(ApprovedTradeIntent intent, PlannedOrder order);

    void complete(
            String automationRunId,
            TradeAutomationStatus status,
            TradeDecision decision,
            TradeProposal proposal,
            List<StoredRiskAssessment> assessments,
            List<PlannedOrder> orders,
            List<String> reasons,
            Instant completedAt);

    void fail(
            String automationRunId,
            String errorCode,
            String safeMessage,
            Instant failedAt);

    void recordExecutionResults(
            String automationRunId,
            List<PaperOrderExecutionResult> results,
            Instant completedAt);
}
