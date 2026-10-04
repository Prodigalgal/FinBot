package io.omnnu.finbot.domain.paper;

import io.omnnu.finbot.domain.trading.DirectionalAction;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.Objects;

public record PaperTradeTerms(
        DirectionalAction side,
        BigDecimal quantity,
        BigDecimal contractSize,
        BigDecimal limitPrice,
        BigDecimal targetPrice,
        BigDecimal stopPrice,
        BigDecimal leverage,
        BigDecimal feeRate,
        BigDecimal slippageRate,
        BigDecimal liquidationBufferRate,
        String policyVersion,
        Instant expiresAt) {
    public PaperTradeTerms {
        Objects.requireNonNull(side, "side");
        positive(quantity, "quantity");
        positive(contractSize, "contractSize");
        positive(limitPrice, "limitPrice");
        positive(targetPrice, "targetPrice");
        positive(stopPrice, "stopPrice");
        positive(leverage, "leverage");
        rate(feeRate, "feeRate");
        rate(slippageRate, "slippageRate");
        rate(liquidationBufferRate, "liquidationBufferRate");
        if (leverage.compareTo(BigDecimal.ONE) < 0) throw new IllegalArgumentException("leverage must be at least one");
        var validPrices = side == DirectionalAction.BUY
                ? targetPrice.compareTo(limitPrice) > 0 && stopPrice.compareTo(limitPrice) < 0
                : targetPrice.compareTo(limitPrice) < 0 && stopPrice.compareTo(limitPrice) > 0;
        if (!validPrices) throw new IllegalArgumentException("Paper price levels contradict the direction");
        policyVersion = Objects.requireNonNull(policyVersion, "policyVersion").strip();
        if (policyVersion.isEmpty()) throw new IllegalArgumentException("policyVersion must not be blank");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public BigDecimal notional(BigDecimal price) {
        return quantity.multiply(contractSize).multiply(price, MathContext.DECIMAL128);
    }

    public BigDecimal margin(BigDecimal price) {
        return notional(price).divide(leverage, MathContext.DECIMAL128);
    }

    public BigDecimal reservation(BigDecimal price) {
        return margin(price).add(notional(price).multiply(feeRate).multiply(BigDecimal.valueOf(2)));
    }

    private static void positive(BigDecimal value, String field) {
        if (Objects.requireNonNull(value, field).signum() <= 0) throw new IllegalArgumentException(field + " must be positive");
    }

    private static void rate(BigDecimal value, String field) {
        if (Objects.requireNonNull(value, field).signum() < 0 || value.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException(field + " must be between zero and one");
        }
    }
}
