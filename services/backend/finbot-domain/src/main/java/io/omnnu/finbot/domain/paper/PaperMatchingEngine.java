package io.omnnu.finbot.domain.paper;

import static io.omnnu.finbot.domain.paper.PaperTrade.Status;
import static io.omnnu.finbot.domain.paper.PaperTrade.Health;
import static io.omnnu.finbot.domain.paper.PaperTradeEvent.Type;

import io.omnnu.finbot.domain.trading.DirectionalAction;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class PaperMatchingEngine {
    public static final String MODEL_VERSION = "LOCAL_PAPER_V1";
    private static final Duration MAXIMUM_QUOTE_AGE = Duration.ofSeconds(30);
    private static final BigDecimal MAXIMUM_BAR_PARTICIPATION = new BigDecimal("0.01");

    public PaperMatchingResult evaluate(PaperTrade trade, PaperMarketObservation market, Instant now) {
        Objects.requireNonNull(trade, "trade");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(now, "now");
        if (!trade.active()) return new PaperMatchingResult(trade, List.of());
        var state = new Progress(trade);
        var quote = market.quote();
        if (!validQuote(trade, quote, now)) return state.result(Health.QUOTE_UNAVAILABLE);
        if (market.fundingCompleteThrough().isBefore(quote.occurredAt())) {
            return state.result(Health.FUNDING_DATA_PENDING);
        }
        var completedThrough = quote.occurredAt().truncatedTo(ChronoUnit.MINUTES);
        var bars = market.bars().stream()
                .filter(bar -> bar.openTime().isAfter(trade.candleCursor())
                        && !bar.closeTime().isAfter(completedThrough))
                .sorted(Comparator.comparing(PaperMarketObservation.Bar::openTime))
                .toList();
        // Check the entire replay range before creating any event; a missing minute cannot be inferred.
        var expected = trade.candleCursor().plusSeconds(60);
        for (var bar : bars) {
            if (!bar.openTime().equals(expected)) return state.result(Health.CANDLE_DATA_GAP);
            expected = expected.plusSeconds(60);
        }
        if (expected.isBefore(completedThrough)) return state.result(Health.CANDLE_DATA_GAP);
        for (var bar : bars) {
            if (!state.active()) break;
            state.applyBar(bar);
            if (bar.openTime().isAfter(state.candleCursor)) state.candleCursor = bar.openTime();
        }
        if (state.active()) state.applyQuote(quote);
        state.applyFunding(market.funding());
        state.fundingCursor = quote.occurredAt();
        state.quoteAt = quote.occurredAt();
        state.markPrice = quote.markPrice();
        return state.result(Health.READY);
    }

    public PaperMatchingResult cancel(PaperTrade trade, Instant now) {
        if (trade.status() == Status.CANCELLED) return new PaperMatchingResult(trade, List.of());
        if (trade.status() != Status.PENDING_ENTRY) throw new IllegalStateException("Only pending paper orders can be cancelled");
        var state = new Progress(trade);
        state.status = Status.CANCELLED;
        state.closedAt = now;
        state.event(Type.CANCELLED, now, null, BigDecimal.ZERO, BigDecimal.ZERO, "USER_CANCEL", "LOCAL");
        return state.result(Health.READY);
    }

    public PaperMatchingResult close(PaperTrade trade, PaperMarketObservation market, Instant now) {
        if (trade.status() == Status.CLOSED) return new PaperMatchingResult(trade, List.of());
        if (trade.status() != Status.OPEN) throw new IllegalStateException("Only open paper positions can be closed");
        var evaluated = evaluate(trade, market, now);
        if (evaluated.trade().healthCode() != Health.READY) {
            throw new IllegalStateException("Paper position cannot be closed with incomplete market data");
        }
        if (!evaluated.trade().active()) return evaluated;
        var state = new Progress(evaluated.trade());
        state.exitAtQuote(market.quote(), "USER_CLOSE");
        var events = new ArrayList<>(evaluated.events());
        events.addAll(state.events);
        return new PaperMatchingResult(state.result(Health.READY).trade(), events);
    }

    private static boolean validQuote(PaperTrade trade, PaperMarketObservation.Quote quote, Instant now) {
        return trade.symbol().equals(quote.symbol())
                && !quote.occurredAt().isBefore(trade.createdAt())
                && !quote.occurredAt().isAfter(now)
                && !quote.observedAt().isAfter(now)
                && !quote.observedAt().isBefore(quote.occurredAt())
                && !quote.occurredAt().isBefore(now.minus(MAXIMUM_QUOTE_AGE))
                && (trade.quoteAt() == null || quote.occurredAt().isAfter(trade.quoteAt()));
    }

    private static final class Progress {
        private final PaperTrade original;
        private final PaperTradeTerms terms;
        private final List<PaperTradeEvent> events = new ArrayList<>();
        private Status status;
        private Instant enteredAt;
        private BigDecimal entryPrice;
        private Instant closedAt;
        private BigDecimal exitPrice;
        private Instant candleCursor;
        private Instant fundingCursor;
        private Instant quoteAt;
        private BigDecimal markPrice;
        private BigDecimal fees;
        private BigDecimal funding;
        private BigDecimal realized;

        private Progress(PaperTrade trade) {
            original = trade;
            terms = trade.terms();
            status = trade.status();
            enteredAt = trade.enteredAt();
            entryPrice = trade.entryPrice();
            closedAt = trade.closedAt();
            exitPrice = trade.exitPrice();
            candleCursor = trade.candleCursor();
            fundingCursor = trade.fundingCursor();
            quoteAt = trade.quoteAt();
            markPrice = trade.markPrice();
            fees = trade.feesUsdt();
            funding = trade.fundingUsdt();
            realized = trade.realizedPnlUsdt();
        }

        private boolean active() { return status == Status.PENDING_ENTRY || status == Status.OPEN; }
        private boolean buy() { return terms.side() == DirectionalAction.BUY; }

        private void applyBar(PaperMarketObservation.Bar bar) {
            if (status == Status.PENDING_ENTRY) {
                if (!bar.closeTime().isBefore(terms.expiresAt())) return;
                var enoughVolume = bar.volume().multiply(MAXIMUM_BAR_PARTICIPATION).compareTo(terms.quantity()) >= 0;
                var adjustedLow = bar.low().multiply(BigDecimal.ONE.add(terms.slippageRate()));
                var adjustedHigh = bar.high().multiply(BigDecimal.ONE.subtract(terms.slippageRate()));
                var crossed = buy() ? adjustedLow.compareTo(terms.limitPrice()) < 0 : adjustedHigh.compareTo(terms.limitPrice()) > 0;
                if (!crossed || !enoughVolume) return;
                enter(terms.limitPrice(), bar.closeTime(), "BAR_RANGE_CONSERVATIVE", bar.sourceEndpoint(), false);
                // The entry minute has no observed path. Never award a same-minute profit.
                if (stopTouched(bar)) exit(stopExit(bar), bar.closeTime(), "ENTRY_BAR_STOP_ASSUMED", bar.sourceEndpoint());
                return;
            }
            if (bar.openTime().isBefore(enteredAt)) return;
            if (!bar.openTime().isBefore(terms.expiresAt())) {
                exit(adverse(bar.open()), bar.openTime(), "TIME_EXIT_BAR_ESTIMATE", bar.sourceEndpoint());
            } else if (liquidated(bar.markOpen())) {
                exit(adverse(bar.open()), bar.openTime(), "GAP_LIQUIDATION_ESTIMATE", bar.sourceEndpoint());
            } else if (stopTouched(bar)) {
                exit(stopExit(bar), bar.closeTime(), targetTouched(bar) ? "AMBIGUOUS_STOP_FIRST" : "STOP_BAR_ESTIMATE", bar.sourceEndpoint());
            } else if (liquidated(buy() ? bar.markLow() : bar.markHigh())) {
                exit(adverse(liquidationPrice()), bar.closeTime(), "LIQUIDATION_BAR_ESTIMATE", bar.sourceEndpoint());
            } else if (targetTouched(bar)) {
                exit(adverse(terms.targetPrice()), bar.closeTime(), "TARGET_BAR_ESTIMATE", bar.sourceEndpoint());
            }
        }

        private void applyQuote(PaperMarketObservation.Quote quote) {
            if (status == Status.PENDING_ENTRY) {
                if (!quote.occurredAt().isBefore(terms.expiresAt())) {
                    status = Status.EXPIRED;
                    closedAt = quote.occurredAt();
                    event(Type.EXPIRED, closedAt, null, BigDecimal.ZERO, BigDecimal.ZERO, "UNFILLED_EXPIRY", quote.sourceEndpoint());
                    return;
                }
                var executable = buy() ? quote.ask() : quote.bid();
                var size = buy() ? quote.askSize() : quote.bidSize();
                var crosses = buy() ? executable.compareTo(terms.limitPrice()) <= 0 : executable.compareTo(terms.limitPrice()) >= 0;
                if (crosses && size.compareTo(terms.quantity()) >= 0) {
                    var price = buy() ? executable.multiply(BigDecimal.ONE.add(terms.slippageRate())).min(terms.limitPrice())
                            : executable.multiply(BigDecimal.ONE.subtract(terms.slippageRate())).max(terms.limitPrice());
                    enter(price, quote.occurredAt(), "TOP_OF_BOOK_FULL_SIZE_ESTIMATE", quote.sourceEndpoint(), true);
                }
            }
            if (status != Status.OPEN) return;
            var exitQuote = buy() ? quote.bid() : quote.ask();
            if (liquidated(quote.markPrice())) exitAtQuote(quote, "LIQUIDATION_QUOTE_ESTIMATE");
            else if (buy() ? exitQuote.compareTo(terms.stopPrice()) <= 0 : exitQuote.compareTo(terms.stopPrice()) >= 0) exitAtQuote(quote, "STOP_QUOTE");
            else if (buy() ? exitQuote.compareTo(terms.targetPrice()) >= 0 : exitQuote.compareTo(terms.targetPrice()) <= 0) exitAtQuote(quote, "TARGET_QUOTE");
            else if (!quote.occurredAt().isBefore(terms.expiresAt())) exitAtQuote(quote, "TIME_EXIT_QUOTE");
        }

        private void enter(BigDecimal price, Instant at, String model, String source, boolean quoteFill) {
            status = Status.OPEN;
            enteredAt = at;
            entryPrice = price;
            // Skip the full entry candle: its high/low may precede this quote-based fill.
            var entryCursor = quoteFill ? at.truncatedTo(ChronoUnit.MINUTES) : at.minusSeconds(60);
            if (entryCursor.isAfter(candleCursor)) candleCursor = entryCursor;
            var fee = money(terms.notional(price).multiply(terms.feeRate()));
            fees = fees.add(fee);
            realized = realized.subtract(fee);
            event(Type.ENTRY_FILL, at, price, fee.negate(), fee, model, source);
        }

        private void exitAtQuote(PaperMarketObservation.Quote quote, String model) {
            exit(adverse(buy() ? quote.bid() : quote.ask()), quote.occurredAt(), model, quote.sourceEndpoint());
        }

        private void exit(BigDecimal price, Instant at, String model, String source) {
            status = Status.CLOSED;
            closedAt = at;
            exitPrice = price;
            var distance = buy() ? price.subtract(entryPrice) : entryPrice.subtract(price);
            var gross = money(distance.multiply(terms.quantity()).multiply(terms.contractSize()));
            var fee = money(terms.notional(price).multiply(terms.feeRate()));
            fees = fees.add(fee);
            realized = realized.add(gross).subtract(fee);
            event(Type.EXIT_FILL, at, price, gross.subtract(fee), fee, model, source);
        }

        private void applyFunding(List<PaperMarketObservation.Funding> rates) {
            if (enteredAt == null) return;
            for (var rate : rates.stream().sorted(Comparator.comparing(PaperMarketObservation.Funding::occurredAt)).toList()) {
                if (!rate.occurredAt().isAfter(fundingCursor) || rate.occurredAt().isBefore(enteredAt)
                        || (closedAt != null && rate.occurredAt().isAfter(closedAt))) continue;
                var cost = money(terms.notional(rate.markPrice()).multiply(rate.rate()).multiply(buy() ? BigDecimal.ONE : BigDecimal.ONE.negate()));
                // A bar fill/exit stamped at the funding boundary has an unknown path; never grant an ambiguous credit.
                if (cost.signum() < 0 && (rate.occurredAt().equals(enteredAt) || rate.occurredAt().equals(closedAt))) continue;
                funding = funding.add(cost);
                realized = realized.subtract(cost);
                event(Type.FUNDING, rate.occurredAt(), rate.markPrice(), cost.negate(), BigDecimal.ZERO, "HISTORICAL_RATE_MARK_OPEN_ESTIMATE", rate.sourceEndpoint());
            }
        }

        private boolean stopTouched(PaperMarketObservation.Bar bar) {
            return buy() ? bar.low().compareTo(terms.stopPrice()) <= 0 : bar.high().compareTo(terms.stopPrice()) >= 0;
        }

        private static BigDecimal money(BigDecimal value) {
            return value.setScale(18, java.math.RoundingMode.HALF_EVEN);
        }

        private boolean targetTouched(PaperMarketObservation.Bar bar) {
            return buy() ? bar.high().multiply(BigDecimal.ONE.subtract(terms.slippageRate())).compareTo(terms.targetPrice()) >= 0
                    : bar.low().multiply(BigDecimal.ONE.add(terms.slippageRate())).compareTo(terms.targetPrice()) <= 0;
        }

        private BigDecimal stopExit(PaperMarketObservation.Bar bar) {
            return adverse(buy() ? bar.open().min(terms.stopPrice()) : bar.open().max(terms.stopPrice()));
        }

        private BigDecimal adverse(BigDecimal price) {
            return price.multiply(buy() ? BigDecimal.ONE.subtract(terms.slippageRate()) : BigDecimal.ONE.add(terms.slippageRate()));
        }

        private BigDecimal liquidationPrice() {
            var distance = BigDecimal.ONE.divide(terms.leverage(), MathContext.DECIMAL128)
                    .subtract(terms.liquidationBufferRate()).subtract(terms.feeRate());
            return entryPrice.multiply(buy() ? BigDecimal.ONE.subtract(distance) : BigDecimal.ONE.add(distance));
        }

        private boolean liquidated(BigDecimal price) {
            return buy() ? price.compareTo(liquidationPrice()) <= 0 : price.compareTo(liquidationPrice()) >= 0;
        }

        private void event(Type type, Instant at, BigDecimal price, BigDecimal cash, BigDecimal fee, String model, String source) {
            var id = original.tradeId() + '_' + type.name().toLowerCase(java.util.Locale.ROOT) + '_' + at.toEpochMilli();
            events.add(new PaperTradeEvent(id, original.tradeId(), type, at, price, cash, fee, MODEL_VERSION + ':' + model, source));
        }

        private PaperMatchingResult result(Health health) {
            return new PaperMatchingResult(new PaperTrade(original.tradeId(), original.projectionId(), original.instrumentId(), original.symbol(),
                    terms, status, original.createdAt(), enteredAt, entryPrice, closedAt, exitPrice, candleCursor, fundingCursor,
                    quoteAt, markPrice, fees, funding, realized, health, original.version() + 1), events);
        }
    }
}
