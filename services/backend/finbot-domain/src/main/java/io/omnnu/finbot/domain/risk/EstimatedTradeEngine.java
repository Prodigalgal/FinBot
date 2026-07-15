package io.omnnu.finbot.domain.risk;

import io.omnnu.finbot.domain.trading.Confidence;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import io.omnnu.finbot.domain.trading.TradeProposal;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class EstimatedTradeEngine {
    private static final MathContext CALCULATION = new MathContext(24, RoundingMode.HALF_EVEN);
    private static final BigDecimal MAXIMUM_ENTRY_DEVIATION = new BigDecimal("0.02");

    public EstimatedTradePlan estimate(
            TradeProposal proposal,
            Confidence confidence,
            ProjectionInstrumentSpec instrument,
            RiskPolicy policy) {
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(policy, "policy");

        var reasons = validate(proposal, confidence, instrument, policy);
        if (!reasons.isEmpty()) {
            return EstimatedTradePlan.blocked(reasons);
        }

        var entry = proposal.entryReference().value();
        var target = proposal.targetPrice().value();
        var stop = proposal.invalidationPrice().value();
        var contractSize = instrument.contractSize();
        var sideCostRate = policy.takerFeeRate().add(policy.slippageRate());

        var lossPerQuantity = entry.subtract(stop)
                .abs()
                .multiply(contractSize, CALCULATION)
                .add(entry.add(stop)
                        .multiply(contractSize, CALCULATION)
                        .multiply(sideCostRate, CALCULATION));
        var quantityByRisk = policy.riskBudgetUsdt().divide(lossPerQuantity, CALCULATION);
        var quantityByNotional = policy.maximumNotionalUsdt()
                .divide(entry.multiply(contractSize, CALCULATION), CALCULATION);
        var quantity = floorToStep(quantityByRisk.min(quantityByNotional), instrument.quantityStep());
        if (quantity.compareTo(instrument.minimumQuantity()) < 0) {
            return EstimatedTradePlan.blocked(List.of("风险预算不足以满足产品最小预估数量"));
        }

        var notional = quantity.multiply(entry, CALCULATION).multiply(contractSize, CALCULATION);
        var targetNotional = quantity.multiply(target, CALCULATION).multiply(contractSize, CALCULATION);
        var stopNotional = quantity.multiply(stop, CALCULATION).multiply(contractSize, CALCULATION);
        var entryCost = notional.multiply(sideCostRate, CALCULATION);
        var targetExitCost = targetNotional.multiply(sideCostRate, CALCULATION);
        var stopExitCost = stopNotional.multiply(sideCostRate, CALCULATION);
        var grossProfit = directionalDistance(proposal.action(), entry, target)
                .multiply(quantity, CALCULATION)
                .multiply(contractSize, CALCULATION);
        var grossLoss = entry.subtract(stop)
                .abs()
                .multiply(quantity, CALCULATION)
                .multiply(contractSize, CALCULATION);
        var estimatedProfit = grossProfit.subtract(entryCost, CALCULATION).subtract(targetExitCost, CALCULATION);
        var estimatedLoss = grossLoss.add(entryCost, CALCULATION).add(stopExitCost, CALCULATION);
        if (estimatedProfit.signum() <= 0) {
            return EstimatedTradePlan.blocked(List.of("止盈空间不足以覆盖预估手续费与滑点"));
        }

        var stopDistance = entry.subtract(stop).abs().divide(entry, CALCULATION);
        var safeLeverageDenominator = stopDistance
                .add(policy.liquidationBufferRate())
                .add(policy.takerFeeRate())
                .add(policy.slippageRate());
        var riskDerivedMaximumLeverage = BigDecimal.ONE.divide(safeLeverageDenominator, CALCULATION)
                .setScale(0, RoundingMode.FLOOR)
                .min(policy.maximumLeverage())
                .min(instrument.venueMaximumLeverage());
        var leverage = policy.preferredLeverage().min(riskDerivedMaximumLeverage);
        if (leverage.compareTo(BigDecimal.ONE) < 0) {
            return EstimatedTradePlan.blocked(List.of("止损距离与费用导致不存在至少 1x 的可用预估杠杆"));
        }
        var liquidationDistance = BigDecimal.ONE.divide(leverage, CALCULATION)
                .subtract(policy.takerFeeRate())
                .subtract(policy.liquidationBufferRate());
        if (liquidationDistance.signum() <= 0 || !stopPrecedesLiquidation(proposal, entry, stop, liquidationDistance)) {
            return EstimatedTradePlan.blocked(List.of("止损价未安全位于预估强平价之前"));
        }

        var initialMargin = notional.divide(leverage, CALCULATION);
        var riskRewardRatio = estimatedProfit.divide(estimatedLoss, CALCULATION);
        return new EstimatedTradePlan(
                EstimatedTradePlanStatus.ESTIMATED,
                List.of("基于最新市场价、风险预算、手续费和滑点生成，不代表交易所成交"),
                normalized(quantity),
                normalized(notional),
                normalized(leverage),
                normalized(initialMargin),
                normalized(entryCost),
                normalized(targetExitCost),
                normalized(stopExitCost),
                normalized(estimatedProfit),
                normalized(estimatedLoss),
                normalized(riskRewardRatio));
    }

    private static List<String> validate(
            TradeProposal proposal,
            Confidence confidence,
            ProjectionInstrumentSpec instrument,
            RiskPolicy policy) {
        var reasons = new ArrayList<String>();
        if (instrument.currentPrice().isEmpty()) {
            reasons.add("仅研究产品缺少最新市场价格，无法生成预估交易");
            return List.copyOf(reasons);
        }
        if (confidence.value().compareTo(policy.minimumConfidence()) < 0) {
            reasons.add("决策置信度低于风险策略门槛");
        }
        var entry = proposal.entryReference().value();
        var target = proposal.targetPrice().value();
        var stop = proposal.invalidationPrice().value();
        var directionValid = proposal.action() == DirectionalAction.BUY
                ? target.compareTo(entry) > 0 && stop.compareTo(entry) < 0
                : target.compareTo(entry) < 0 && stop.compareTo(entry) > 0;
        if (!directionValid) {
            reasons.add("目标价或止损价与交易方向矛盾");
        }
        var stopDistance = entry.subtract(stop).abs().divide(entry, CALCULATION);
        if (stopDistance.compareTo(policy.maximumStopDistance()) > 0) {
            reasons.add("止损距离超过风险策略上限");
        }
        var currentPrice = instrument.currentPrice().orElseThrow().value();
        var currentDeviation = entry.subtract(currentPrice).abs().divide(currentPrice, CALCULATION);
        if (currentDeviation.compareTo(MAXIMUM_ENTRY_DEVIATION) > 0) {
            reasons.add("AI 入场参考价偏离最新市场价超过 2%");
        }
        return List.copyOf(reasons);
    }

    private static BigDecimal directionalDistance(
            DirectionalAction action,
            BigDecimal entry,
            BigDecimal target) {
        return action == DirectionalAction.BUY
                ? target.subtract(entry, CALCULATION)
                : entry.subtract(target, CALCULATION);
    }

    private static boolean stopPrecedesLiquidation(
            TradeProposal proposal,
            BigDecimal entry,
            BigDecimal stop,
            BigDecimal liquidationDistance) {
        var liquidationPrice = proposal.action() == DirectionalAction.BUY
                ? entry.multiply(BigDecimal.ONE.subtract(liquidationDistance), CALCULATION)
                : entry.multiply(BigDecimal.ONE.add(liquidationDistance), CALCULATION);
        return liquidationPrice.signum() > 0 && (proposal.action() == DirectionalAction.BUY
                ? stop.compareTo(liquidationPrice) > 0
                : stop.compareTo(liquidationPrice) < 0);
    }

    private static BigDecimal floorToStep(BigDecimal value, BigDecimal step) {
        return value.divide(step, 0, RoundingMode.FLOOR).multiply(step);
    }

    private static BigDecimal normalized(BigDecimal value) {
        return value.stripTrailingZeros();
    }
}
