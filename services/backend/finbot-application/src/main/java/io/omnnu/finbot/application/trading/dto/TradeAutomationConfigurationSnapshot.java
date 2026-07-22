package io.omnnu.finbot.application.trading.dto;

import io.omnnu.finbot.domain.risk.RiskPolicy;
import java.util.List;

public record TradeAutomationConfigurationSnapshot(
        List<TradeExecutionAiStageConfig> aiStages,
        RiskPolicy activeRiskPolicy) {
    public TradeAutomationConfigurationSnapshot {
        aiStages = List.copyOf(aiStages);
    }
}
