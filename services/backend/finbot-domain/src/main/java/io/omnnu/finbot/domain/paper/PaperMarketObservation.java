package io.omnnu.finbot.domain.paper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record PaperMarketObservation(Quote quote, List<Bar> bars, List<Funding> funding, Instant fundingCompleteThrough) {
    public PaperMarketObservation {
        Objects.requireNonNull(quote, "quote");
        bars = List.copyOf(bars);
        funding = List.copyOf(funding);
        Objects.requireNonNull(fundingCompleteThrough, "fundingCompleteThrough");
        if (funding.stream().map(Funding::occurredAt).distinct().count() != funding.size()
                || funding.stream().anyMatch(rate -> rate.occurredAt().isAfter(quote.occurredAt()))) {
            throw new IllegalArgumentException("Duplicate or future funding observations");
        }
    }

    public record Quote(String symbol, BigDecimal bid, BigDecimal ask, BigDecimal bidSize,
                        BigDecimal askSize, BigDecimal markPrice, Instant occurredAt,
                        Instant observedAt, String sourceEndpoint) {
        public Quote {
            Objects.requireNonNull(symbol, "symbol");
            positive(bid, "bid");
            positive(ask, "ask");
            positive(markPrice, "markPrice");
            nonnegative(bidSize, "bidSize");
            nonnegative(askSize, "askSize");
            if (bid.compareTo(ask) > 0) throw new IllegalArgumentException("Crossed paper quote");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(observedAt, "observedAt");
            Objects.requireNonNull(sourceEndpoint, "sourceEndpoint");
        }
    }

    public record Bar(Instant openTime, BigDecimal open, BigDecimal high, BigDecimal low,
                      BigDecimal close, BigDecimal volume, BigDecimal markOpen,
                      BigDecimal markHigh, BigDecimal markLow, String sourceEndpoint) {
        public Bar {
            Objects.requireNonNull(openTime, "openTime");
            positive(open, "open");
            positive(high, "high");
            positive(low, "low");
            positive(close, "close");
            positive(markOpen, "markOpen");
            positive(markHigh, "markHigh");
            positive(markLow, "markLow");
            nonnegative(volume, "volume");
            if (low.compareTo(open.min(close)) > 0 || high.compareTo(open.max(close)) < 0
                    || markLow.compareTo(markOpen) > 0 || markHigh.compareTo(markOpen) < 0) {
                throw new IllegalArgumentException("Inconsistent paper candle");
            }
            Objects.requireNonNull(sourceEndpoint, "sourceEndpoint");
        }

        public Instant closeTime() { return openTime.plusSeconds(60); }
    }

    public record Funding(Instant occurredAt, BigDecimal rate, BigDecimal markPrice, String sourceEndpoint) {
        public Funding {
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(rate, "rate");
            positive(markPrice, "markPrice");
            Objects.requireNonNull(sourceEndpoint, "sourceEndpoint");
        }
    }

    private static void positive(BigDecimal value, String field) {
        if (Objects.requireNonNull(value, field).signum() <= 0) throw new IllegalArgumentException(field + " must be positive");
    }

    private static void nonnegative(BigDecimal value, String field) {
        if (Objects.requireNonNull(value, field).signum() < 0) throw new IllegalArgumentException(field + " must be nonnegative");
    }
}
