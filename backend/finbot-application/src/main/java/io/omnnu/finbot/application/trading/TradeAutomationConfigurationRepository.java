package io.omnnu.finbot.application.trading;

import io.omnnu.finbot.domain.risk.RiskPolicy;

public interface TradeAutomationConfigurationRepository {
    TradeAutomationConfigurationSnapshot snapshot();

    TradeExecutionAiStageConfig updateAiStage(TradeExecutionAiStageConfig config, long expectedVersion);

    RiskPolicy activateRiskPolicy(RiskPolicy policy);
}
