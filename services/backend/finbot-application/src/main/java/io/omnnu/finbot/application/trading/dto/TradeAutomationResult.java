package io.omnnu.finbot.application.trading.dto;

import io.omnnu.finbot.domain.oms.OrderId;
import io.omnnu.finbot.domain.trading.TradeDecisionId;
import java.util.List;

public record TradeAutomationResult(
        String automationRunId,
        TradeAutomationStatus status,
        TradeDecisionId decisionId,
        List<OrderId> plannedOrderIds,
        List<String> reasons) {
    public TradeAutomationResult {
        plannedOrderIds = List.copyOf(plannedOrderIds);
        reasons = List.copyOf(reasons);
    }
}
