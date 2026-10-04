package io.omnnu.finbot.domain.paper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record PaperTrade(
        String tradeId,
        String projectionId,
        String instrumentId,
        String symbol,
        PaperTradeTerms terms,
        Status status,
        Instant createdAt,
        Instant enteredAt,
        BigDecimal entryPrice,
        Instant closedAt,
        BigDecimal exitPrice,
        Instant candleCursor,
        Instant fundingCursor,
        Instant quoteAt,
        BigDecimal markPrice,
        BigDecimal feesUsdt,
        BigDecimal fundingUsdt,
        BigDecimal realizedPnlUsdt,
        Health healthCode,
        long version) {
    public enum Status { PENDING_ENTRY, OPEN, CLOSED, CANCELLED, EXPIRED }
    public enum Health { READY, QUOTE_UNAVAILABLE, FUNDING_DATA_PENDING, CANDLE_DATA_GAP, MARKET_UNAVAILABLE, RISK_BLOCKED }

    public PaperTrade {
        requireText(tradeId, "tradeId");
        requireText(projectionId, "projectionId");
        requireText(instrumentId, "instrumentId");
        requireText(symbol, "symbol");
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(candleCursor, "candleCursor");
        Objects.requireNonNull(fundingCursor, "fundingCursor");
        Objects.requireNonNull(feesUsdt, "feesUsdt");
        Objects.requireNonNull(fundingUsdt, "fundingUsdt");
        Objects.requireNonNull(realizedPnlUsdt, "realizedPnlUsdt");
        Objects.requireNonNull(healthCode, "healthCode");
        if (version < 0 || feesUsdt.signum() < 0 || !terms.expiresAt().isAfter(createdAt)) {
            throw new IllegalArgumentException("Invalid paper trade state");
        }
        if ((status == Status.OPEN || status == Status.CLOSED)
                && (enteredAt == null || entryPrice == null || entryPrice.signum() <= 0)) {
            throw new IllegalArgumentException("Filled paper trade requires an entry");
        }
        if (status == Status.CLOSED && (closedAt == null || exitPrice == null || exitPrice.signum() <= 0)) {
            throw new IllegalArgumentException("Closed paper trade requires an exit");
        }
    }

    public boolean active() {
        return status == Status.PENDING_ENTRY || status == Status.OPEN;
    }

    public BigDecimal reservationUsdt() {
        return active() ? terms.reservation(entryPrice == null ? terms.limitPrice() : entryPrice) : BigDecimal.ZERO;
    }

    public BigDecimal unrealizedPnlUsdt() {
        if (status != Status.OPEN || markPrice == null) return BigDecimal.ZERO;
        var difference = terms.side() == io.omnnu.finbot.domain.trading.DirectionalAction.BUY
                ? markPrice.subtract(entryPrice) : entryPrice.subtract(markPrice);
        return difference.multiply(terms.quantity()).multiply(terms.contractSize());
    }

    private static void requireText(String value, String field) {
        if (Objects.requireNonNull(value, field).isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
