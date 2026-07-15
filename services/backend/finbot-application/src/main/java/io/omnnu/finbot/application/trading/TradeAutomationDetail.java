package io.omnnu.finbot.application.trading;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.oms.OrderStatus;
import io.omnnu.finbot.domain.risk.RiskAssessmentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TradeAutomationDetail(
        Summary summary,
        Decision decision,
        List<AiReview> aiReviews,
        List<RiskAssessment> riskAssessments,
        List<EstimatedTrade> estimatedTrades,
        List<Order> orders) {
    public TradeAutomationDetail {
        aiReviews = List.copyOf(aiReviews);
        riskAssessments = List.copyOf(riskAssessments);
        estimatedTrades = List.copyOf(estimatedTrades);
        orders = List.copyOf(orders);
    }

    public record Summary(
            String automationRunId,
            String workflowRunId,
            TradeAutomationStatus status,
            String symbol,
            String action,
            BigDecimal confidence,
            int orderCount,
            int estimatedTradeCount,
            String errorCode,
            String statusMessage,
            Instant startedAt,
            Instant completedAt) {
    }

    public record Decision(
            String decisionId,
            String decisionKind,
            String symbol,
            String action,
            BigDecimal confidence,
            BigDecimal entryReference,
            BigDecimal targetPrice,
            BigDecimal invalidationPrice,
            String rationaleJson,
            String proposalId,
            String proposalStatus,
            Instant createdAt) {
    }

    public record AiReview(
            String reviewId,
            TradeExecutionAiStage stage,
            String status,
            String invocationId,
            String providerProfileId,
            String modelName,
            ReasoningEffort reasoningEffort,
            String outputJson,
            String outputHash,
            String errorCode,
            String errorMessage,
            Instant createdAt) {
    }

    public record RiskAssessment(
            String assessmentId,
            String proposalId,
            String accountId,
            String policyVersion,
            RiskAssessmentStatus status,
            String reasonsJson,
            BigDecimal quantity,
            BigDecimal notionalUsdt,
            BigDecimal leverage,
            BigDecimal initialMarginUsdt,
            BigDecimal estimatedMaximumLossUsdt,
            BigDecimal approximateLiquidationPrice,
            Instant assessedAt) {
    }

    public record EstimatedTrade(
            String projectionId,
            String proposalId,
            String instrumentId,
            ExchangeVenue exchange,
            String symbol,
            String side,
            String policyVersion,
            BigDecimal entryReference,
            BigDecimal marketPrice,
            BigDecimal targetPrice,
            BigDecimal stopPrice,
            BigDecimal quantity,
            BigDecimal contractSize,
            BigDecimal notionalUsdt,
            BigDecimal leverage,
            BigDecimal initialMarginUsdt,
            BigDecimal estimatedEntryCostUsdt,
            BigDecimal estimatedTargetExitCostUsdt,
            BigDecimal estimatedStopExitCostUsdt,
            BigDecimal estimatedProfitUsdt,
            BigDecimal estimatedLossUsdt,
            BigDecimal riskRewardRatio,
            Instant calculatedAt) {
    }

    public record Order(
            String orderId,
            String intentId,
            ExchangeVenue exchange,
            ExchangeEnvironment environment,
            String accountId,
            String symbol,
            String side,
            OrderStatus status,
            BigDecimal requestedQuantity,
            BigDecimal filledQuantity,
            BigDecimal averageFillPrice,
            BigDecimal leverage,
            String clientOrderId,
            String exchangeOrderId,
            Instant submittedAt,
            Instant terminalAt,
            Instant createdAt,
            Instant updatedAt,
            List<OrderEvent> events,
            List<SubmissionAttempt> submissionAttempts) {
        public Order {
            events = List.copyOf(events);
            submissionAttempts = List.copyOf(submissionAttempts);
        }
    }

    public record OrderEvent(
            String eventId,
            long sequence,
            String eventType,
            String fromStatus,
            String toStatus,
            String payloadJson,
            Instant occurredAt) {
    }

    public record SubmissionAttempt(
            String attemptId,
            int attemptNumber,
            String requestHash,
            String status,
            String exchangeOrderId,
            Integer httpStatus,
            String responseJson,
            String errorCode,
            String errorMessage,
            Instant startedAt,
            Instant completedAt) {
    }
}
