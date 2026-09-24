package io.omnnu.finbot.domain.oms;

import io.omnnu.finbot.domain.risk.RealWorldMarketModel;
import io.omnnu.finbot.domain.risk.RiskPolicy;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

public final class RealWorldOrderMatcher {
    private static final MathContext CALCULATION = new MathContext(24, RoundingMode.HALF_EVEN);

    public MatchingOutcome matchMarketOrder(
            DirectionalAction action,
            BigDecimal quantity,
            BigDecimal contractSize,
            BigDecimal bestBid,
            BigDecimal bestAsk,
            RiskPolicy policy,
            RealWorldMarketModel marketModel) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(contractSize, "contractSize");
        Objects.requireNonNull(bestBid, "bestBid");
        Objects.requireNonNull(bestAsk, "bestAsk");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(marketModel, "marketModel");

        var basePrice = action == DirectionalAction.BUY ? bestAsk : bestBid;
        var notional = quantity.multiply(basePrice, CALCULATION).multiply(contractSize, CALCULATION);
        var impactRatio = notional.divide(marketModel.referenceDepthUsdt(), CALCULATION);
        var effectiveSlippageRate = policy.slippageRate()
                .multiply(BigDecimal.ONE.add(impactRatio.multiply(impactRatio, CALCULATION)), CALCULATION);

        if (effectiveSlippageRate.compareTo(marketModel.maxEffectiveSlippageRate()) > 0) {
            return MatchingOutcome.unfilled("市场深度冲击滑点超过最大允许阈值");
        }

        var slippageMultiplier = action == DirectionalAction.BUY
                ? BigDecimal.ONE.add(effectiveSlippageRate)
                : BigDecimal.ONE.subtract(effectiveSlippageRate);
        var executedPrice = basePrice.multiply(slippageMultiplier, CALCULATION);
        var executedNotional = quantity.multiply(executedPrice, CALCULATION).multiply(contractSize, CALCULATION);
        var feeRate = policy.takerFeeRate();
        var feeUsdt = executedNotional.multiply(feeRate, CALCULATION);

        return MatchingOutcome.filled(
                executedPrice.stripTrailingZeros(),
                quantity.stripTrailingZeros(),
                feeRate.stripTrailingZeros(),
                effectiveSlippageRate.stripTrailingZeros(),
                feeUsdt.stripTrailingZeros(),
                "市价单以盘口深度与非线性滑点冲击成交");
    }

    public MatchingOutcome matchLimitOrder(
            DirectionalAction action,
            BigDecimal quantity,
            BigDecimal contractSize,
            BigDecimal limitPrice,
            BigDecimal bestBid,
            BigDecimal bestAsk,
            BigDecimal subsequentExtremumPrice,
            RiskPolicy policy,
            RealWorldMarketModel marketModel) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(contractSize, "contractSize");
        Objects.requireNonNull(limitPrice, "limitPrice");
        Objects.requireNonNull(bestBid, "bestBid");
        Objects.requireNonNull(bestAsk, "bestAsk");
        Objects.requireNonNull(subsequentExtremumPrice, "subsequentExtremumPrice");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(marketModel, "marketModel");

        if (action == DirectionalAction.BUY) {
            // 限价买单价格如果大于等于卖一价，视为跨价差吃单（Taker）
            if (limitPrice.compareTo(bestAsk) >= 0) {
                return matchMarketOrder(action, quantity, contractSize, bestBid, bestAsk, policy, marketModel);
            }
            // 限价买单挂单：必须随后价格向下触及或穿越 limitPrice 才能完全撮合成交
            if (subsequentExtremumPrice.compareTo(limitPrice) > 0) {
                return MatchingOutcome.unfilled("市场最低价未能向下穿越买单限价，订单未撮合成交");
            }
            var executedNotional = quantity.multiply(limitPrice, CALCULATION).multiply(contractSize, CALCULATION);
            var feeRate = marketModel.makerFeeRate();
            var feeUsdt = executedNotional.multiply(feeRate, CALCULATION);
            return MatchingOutcome.filled(
                    limitPrice.stripTrailingZeros(),
                    quantity.stripTrailingZeros(),
                    feeRate.stripTrailingZeros(),
                    BigDecimal.ZERO,
                    feeUsdt.stripTrailingZeros(),
                    "限价买单被市场深度穿越，以挂单价完全撮合成交");
        } else {
            // 限价卖单价格如果小于等于买一价，视为跨价差吃单（Taker）
            if (limitPrice.compareTo(bestBid) <= 0) {
                return matchMarketOrder(action, quantity, contractSize, bestBid, bestAsk, policy, marketModel);
            }
            // 限价卖单挂单：必须随后价格向上触及或穿越 limitPrice 才能完全撮合成交
            if (subsequentExtremumPrice.compareTo(limitPrice) < 0) {
                return MatchingOutcome.unfilled("市场最高价未能向上穿越卖单限价，订单未撮合成交");
            }
            var executedNotional = quantity.multiply(limitPrice, CALCULATION).multiply(contractSize, CALCULATION);
            var feeRate = marketModel.makerFeeRate();
            var feeUsdt = executedNotional.multiply(feeRate, CALCULATION);
            return MatchingOutcome.filled(
                    limitPrice.stripTrailingZeros(),
                    quantity.stripTrailingZeros(),
                    feeRate.stripTrailingZeros(),
                    BigDecimal.ZERO,
                    feeUsdt.stripTrailingZeros(),
                    "限价卖单被市场深度穿越，以挂单价完全撮合成交");
        }
    }
}
