package io.omnnu.finbot.application.trading;

import io.omnnu.finbot.domain.market.Price;
import io.omnnu.finbot.domain.risk.EstimatedTradePlan;
import io.omnnu.finbot.domain.risk.EstimatedTradePlanStatus;
import io.omnnu.finbot.domain.risk.ProjectionInstrumentSpec;
import io.omnnu.finbot.domain.trading.TradeProposalId;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;
import java.util.Objects;

public record StoredEstimatedTradeProjection(
        String projectionId,
        String automationRunId,
        WorkflowRunId workflowRunId,
        TradeProposalId proposalId,
        DirectionalAction side,
        ProjectionInstrumentSpec instrument,
        String policyVersion,
        Price entryReference,
        Price targetPrice,
        Price stopPrice,
        EstimatedTradePlan plan,
        Instant calculatedAt) {

    public StoredEstimatedTradeProjection {
        projectionId = required(projectionId, "projectionId");
        automationRunId = required(automationRunId, "automationRunId");
        Objects.requireNonNull(workflowRunId, "workflowRunId");
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(instrument, "instrument");
        policyVersion = required(policyVersion, "policyVersion");
        Objects.requireNonNull(entryReference, "entryReference");
        Objects.requireNonNull(targetPrice, "targetPrice");
        Objects.requireNonNull(stopPrice, "stopPrice");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(calculatedAt, "calculatedAt");
        if (plan.status() != EstimatedTradePlanStatus.ESTIMATED) {
            throw new IllegalArgumentException("Only successful estimated trades can be stored");
        }
    }

    private static String required(String value, String name) {
        var normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
