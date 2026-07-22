package io.omnnu.finbot.application.quant.dto;

import io.omnnu.finbot.domain.risk.RiskPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TradeRiskPreview(
        String mode,
        String status,
        List<String> reasons,
        BigDecimal quantity,
        BigDecimal notionalUsdt,
        BigDecimal leverage,
        BigDecimal initialMarginUsdt,
        BigDecimal estimatedMaximumLossUsdt,
        BigDecimal approximateLiquidationPrice,
        BigDecimal estimatedProfitUsdt,
        BigDecimal riskRewardRatio,
        RiskPolicy policy,
        Instant calculatedAt) {
    public TradeRiskPreview {
        reasons = List.copyOf(reasons);
    }
}
