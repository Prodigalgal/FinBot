package io.omnnu.finbot.application.trading;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.risk.RiskAssessmentId;
import io.omnnu.finbot.domain.risk.RiskAssessmentPlan;
import io.omnnu.finbot.domain.trading.TradeProposalId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;

public record StoredRiskAssessment(
        RiskAssessmentId assessmentId,
        String automationRunId,
        WorkflowRunId workflowRunId,
        TradeProposalId proposalId,
        ExchangeAccountId accountId,
        InstrumentId instrumentId,
        ExchangeVenue exchange,
        ExchangeEnvironment environment,
        String policyVersion,
        RiskAssessmentPlan plan,
        Instant assessedAt) {
}
