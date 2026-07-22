package io.omnnu.finbot.application.trading.port.out;

import io.omnnu.finbot.application.trading.dto.TradeAutomationConfigurationSnapshot;
import io.omnnu.finbot.application.trading.dto.TradeExecutionAiStageConfig;

import io.omnnu.finbot.domain.risk.RiskPolicy;

public interface TradeAutomationConfigurationRepository {
    TradeAutomationConfigurationSnapshot snapshot();

    TradeExecutionAiStageConfig updateAiStage(TradeExecutionAiStageConfig config, long expectedVersion);

    RiskPolicy activateRiskPolicy(RiskPolicy policy);
}
