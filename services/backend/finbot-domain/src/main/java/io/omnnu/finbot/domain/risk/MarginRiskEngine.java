package io.omnnu.finbot.domain.risk;

import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.trading.Confidence;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import io.omnnu.finbot.domain.trading.TradeProposal;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;

public final class MarginRiskEngine {
    private static final MathContext CALCULATION = new MathContext(24, RoundingMode.HALF_EVEN);

    public RiskAssessmentPlan assess(
            TradeProposal proposal,
            Confidence confidence,
            RiskInstrumentSpec instrument,
            RiskPolicy policy) {
        var reasons = validate(proposal, confidence, instrument, policy);
        if (!reasons.isEmpty()) {
            return RiskAssessmentPlan.blocked(reasons);
        }

        var entry = proposal.entryReference().value();
        var stopDistance = entry.subtract(proposal.invalidationPrice().value())
                .abs()
                .divide(entry, CALCULATION);
        var roundTripCostRate = policy.takerFeeRate()
                .add(policy.slippageRate())
                .multiply(BigDecimal.TWO);
        var lossRate = stopDistance.add(roundTripCostRate);
        var rawNotional = policy.riskBudgetUsdt().divide(lossRate, CALCULATION);
        var maximumNotional = policy.maximumNotionalUsdt().min(rawNotional);
        var unitNotional = entry.multiply(instrument.contractSize(), CALCULATION);
        var rawQuantity = maximumNotional.divide(unitNotional, CALCULATION);
        var quantity = floorToStep(rawQuantity, instrument.quantityStep());
        if (quantity.compareTo(instrument.minimumQuantity()) < 0) {
            return RiskAssessmentPlan.blocked(java.util.List.of(
                    "风险预算不足以满足交易所最小下单数量"));
        }
        var notional = quantity.multiply(unitNotional, CALCULATION);
        var estimatedLoss = notional.multiply(lossRate, CALCULATION);
        var safeLeverageDenominator = stopDistance
                .add(policy.liquidationBufferRate())
                .add(policy.takerFeeRate())
                .add(policy.slippageRate());
        var safeLeverage = BigDecimal.ONE.divide(safeLeverageDenominator, CALCULATION)
                .setScale(0, RoundingMode.FLOOR)
                .min(policy.maximumLeverage())
                .min(instrument.venueMaximumLeverage());
        if (safeLeverage.compareTo(BigDecimal.ONE) < 0) {
            return RiskAssessmentPlan.blocked(java.util.List.of(
                    "止损距离与费用导致不存在至少 1x 的安全杠杆"));
        }
        var liquidationDistance = BigDecimal.ONE.divide(safeLeverage, CALCULATION)
                .subtract(policy.takerFeeRate())
                .subtract(policy.liquidationBufferRate());
        if (liquidationDistance.signum() <= 0) {
            return RiskAssessmentPlan.blocked(java.util.List.of("估算强平距离无效"));
        }
        var liquidationPrice = proposal.action() == DirectionalAction.BUY
                ? entry.multiply(BigDecimal.ONE.subtract(liquidationDistance), CALCULATION)
                : entry.multiply(BigDecimal.ONE.add(liquidationDistance), CALCULATION);
        var stopBeforeLiquidation = proposal.action() == DirectionalAction.BUY
                ? proposal.invalidationPrice().value().compareTo(liquidationPrice) > 0
                : proposal.invalidationPrice().value().compareTo(liquidationPrice) < 0;
        if (!stopBeforeLiquidation || liquidationPrice.signum() <= 0) {
            return RiskAssessmentPlan.blocked(java.util.List.of("止损价未安全位于估算强平价之前"));
        }
        var initialMargin = notional.divide(safeLeverage, CALCULATION);
        return new RiskAssessmentPlan(
                RiskAssessmentStatus.APPROVED,
                java.util.List.of("通过确定性模拟盘风险门禁"),
                quantity.stripTrailingZeros(),
                notional.stripTrailingZeros(),
                safeLeverage.stripTrailingZeros(),
                initialMargin.stripTrailingZeros(),
                estimatedLoss.stripTrailingZeros(),
                liquidationPrice.stripTrailingZeros());
    }

    private static java.util.List<String> validate(
            TradeProposal proposal,
            Confidence confidence,
            RiskInstrumentSpec instrument,
            RiskPolicy policy) {
        var reasons = new ArrayList<String>();
        if (policy.testEnvironmentOnly()
                && instrument.environment() != ExchangeEnvironment.TESTNET
                && instrument.environment() != ExchangeEnvironment.DEMO) {
            reasons.add("风险策略永久禁止真实盘执行");
        }
        if (confidence.value().compareTo(policy.minimumConfidence()) < 0) {
            reasons.add("决策置信度低于风险策略门槛");
        }
        if (instrument.openPositionCount() >= policy.maximumOpenPositions()) {
            reasons.add("账户未平仓数量已达到策略上限");
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
        var currentDeviation = entry.subtract(instrument.currentPrice().value())
                .abs()
                .divide(instrument.currentPrice().value(), CALCULATION);
        if (currentDeviation.compareTo(new BigDecimal("0.02")) > 0) {
            reasons.add("AI 入场参考价偏离交易所最新价超过 2%");
        }
        return java.util.List.copyOf(reasons);
    }

    private static BigDecimal floorToStep(BigDecimal value, BigDecimal step) {
        return value.divide(step, 0, RoundingMode.FLOOR).multiply(step);
    }
}
