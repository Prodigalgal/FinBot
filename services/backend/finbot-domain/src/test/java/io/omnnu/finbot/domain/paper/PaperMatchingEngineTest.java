package io.omnnu.finbot.domain.paper;

import static org.junit.jupiter.api.Assertions.*;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaperMatchingEngineTest {
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:10Z");
    private final PaperMatchingEngine engine = new PaperMatchingEngine();

    @Test void limitBuyUsesAskAndRequiresFullVisibleSize() {
        var pending = pending(DirectionalAction.BUY, NOW.plusSeconds(600));
        assertEquals(PaperTrade.Status.PENDING_ENTRY, evaluate(pending, quote(NOW.plusSeconds(1), "99", "101", "10")).trade().status());
        assertEquals(PaperTrade.Status.PENDING_ENTRY, evaluate(pending, quote(NOW.plusSeconds(1), "99", "100", "0.5")).trade().status());
        var fill = evaluate(pending, quote(NOW.plusSeconds(1), "99", "100", "10"));
        assertEquals(PaperTrade.Status.OPEN, fill.trade().status());
        decimal("100", fill.trade().entryPrice());
        decimal("-0.1", fill.cashDeltaUsdt());
        assertTrue(fill.events().getFirst().model().contains("FULL_SIZE_ESTIMATE"));
    }

    @Test void limitSellUsesBidAndNeverFillsBelowLimit() {
        var pending = pending(DirectionalAction.SELL, NOW.plusSeconds(600));
        assertEquals(PaperTrade.Status.PENDING_ENTRY, evaluate(pending, quote(NOW.plusSeconds(1), "99", "101", "10")).trade().status());
        var fill = evaluate(pending, quote(NOW.plusSeconds(1), "100", "101", "10"));
        decimal("100", fill.trade().entryPrice());
        assertEquals(PaperTrade.Status.OPEN, fill.trade().status());
    }

    @Test void rejectsStaleFutureForeignAndRepeatedQuotesWithoutCashEffects() {
        var trade = open(DirectionalAction.BUY);
        var observation = quote(NOW.plusSeconds(2), "100", "101", "10");
        assertEquals(PaperTrade.Health.QUOTE_UNAVAILABLE, engine.evaluate(trade,
                observation(observation), NOW.plusSeconds(60)).trade().healthCode());
        assertEquals(PaperTrade.Health.QUOTE_UNAVAILABLE, engine.evaluate(trade,
                observation(observation), NOW).trade().healthCode());
        var duplicate = quote(trade.quoteAt(), "100", "101", "10");
        assertTrue(evaluate(trade, duplicate).events().isEmpty());
        var wrong = new PaperMarketObservation.Quote("XAUTUSDT", bd("100"), bd("101"), bd("10"), bd("10"), bd("100"), NOW.plusSeconds(2), NOW.plusSeconds(2), "PUBLIC");
        assertEquals(PaperTrade.Health.QUOTE_UNAVAILABLE, evaluate(trade, wrong).trade().healthCode());
    }

    @Test void missingCandleBlocksEntireReplayIncludingOtherwiseExecutableQuote() {
        var trade = open(DirectionalAction.BUY);
        var at = NOW.truncatedTo(ChronoUnit.MINUTES).plusSeconds(180);
        var market = new PaperMarketObservation(quote(at, "111", "112", "10"),
                List.of(bar(NOW.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60), "100", "101", "99", "1000")), List.of(), at);
        var result = engine.evaluate(trade, market, at);
        assertEquals(PaperTrade.Health.CANDLE_DATA_GAP, result.trade().healthCode());
        assertEquals(PaperTrade.Status.OPEN, result.trade().status());
        assertTrue(result.events().isEmpty());
        assertEquals(trade.candleCursor(), result.trade().candleCursor());
    }

    @Test void neverUsesQuoteEntryMinutesPreEntryHighOrLow() {
        var trade = open(DirectionalAction.BUY);
        var at = NOW.truncatedTo(ChronoUnit.MINUTES).plusSeconds(70);
        var result = engine.evaluate(trade, new PaperMarketObservation(quote(at, "100", "101", "10"),
                List.of(bar(NOW.truncatedTo(ChronoUnit.MINUTES), "100", "120", "80", "1000")), List.of(), at), at);
        assertEquals(PaperTrade.Status.OPEN, result.trade().status());
        assertTrue(result.events().isEmpty());
    }

    @Test void simultaneousTargetAndStopAssumesStopFirst() {
        var result = replay(open(DirectionalAction.BUY), "100", "112", "94", "1000");
        assertEquals(PaperTrade.Status.CLOSED, result.trade().status());
        assertTrue(result.events().getFirst().model().contains("AMBIGUOUS_STOP_FIRST"));
        decimal("94.905", result.trade().exitPrice());
        assertTrue(result.trade().realizedPnlUsdt().signum() < 0);
    }

    @Test void gapStopUsesWorseOpeningPriceAndSlippage() {
        var result = replay(open(DirectionalAction.BUY), "90", "92", "89", "1000");
        decimal("89.910", result.trade().exitPrice());
    }

    @Test void barEntryCannotEarnAnUnobservedSameBarTarget() {
        var result = replay(pending(DirectionalAction.BUY, NOW.plusSeconds(600)), "100", "112", "99", "1000");
        assertEquals(PaperTrade.Status.OPEN, result.trade().status());
        assertEquals(1, result.events().size());
        assertEquals(PaperTradeEvent.Type.ENTRY_FILL, result.events().getFirst().type());
    }

    @Test void ambiguousEntryBarStopIsConservativelyApplied() {
        var result = replay(pending(DirectionalAction.BUY, NOW.plusSeconds(600)), "100", "112", "94", "1000");
        assertEquals(PaperTrade.Status.CLOSED, result.trade().status());
        assertEquals(2, result.events().size());
        assertTrue(result.events().getLast().model().contains("ENTRY_BAR_STOP_ASSUMED"));
    }

    @Test void barTouchWithoutVolumeOrStrictCrossCannotFill() {
        assertEquals(PaperTrade.Status.PENDING_ENTRY, replay(pending(DirectionalAction.BUY, NOW.plusSeconds(600)), "101", "112", "100", "1000").trade().status());
        assertEquals(PaperTrade.Status.PENDING_ENTRY, replay(pending(DirectionalAction.BUY, NOW.plusSeconds(600)), "101", "112", "99", "1").trade().status());
    }

    @Test void expiryReleasesPendingReservationWithoutFees() {
        var pending = pending(DirectionalAction.BUY, NOW.plusSeconds(2));
        var result = evaluate(pending, quote(NOW.plusSeconds(3), "99", "100", "10"));
        assertEquals(PaperTrade.Status.EXPIRED, result.trade().status());
        decimal("0", result.trade().reservationUsdt());
        decimal("0", result.cashDeltaUsdt());
    }

    @Test void cancellationIsIdempotentAndCannotCancelOpenPositions() {
        var cancelled = engine.cancel(pending(DirectionalAction.BUY, NOW.plusSeconds(600)), NOW.plusSeconds(1));
        assertEquals(PaperTrade.Status.CANCELLED, cancelled.trade().status());
        assertTrue(engine.cancel(cancelled.trade(), NOW.plusSeconds(2)).events().isEmpty());
        assertThrows(IllegalStateException.class, () -> engine.cancel(open(DirectionalAction.BUY), NOW.plusSeconds(2)));
    }

    @Test void explicitCloseAccountsForEntryExitFeesAndAdverseSlip() {
        var trade = open(DirectionalAction.BUY);
        var result = engine.close(trade, observation(quote(NOW.plusSeconds(2), "105", "106", "10")), NOW.plusSeconds(2));
        assertEquals(PaperTrade.Status.CLOSED, result.trade().status());
        decimal("104.895", result.trade().exitPrice());
        decimal("4.690105", result.trade().realizedPnlUsdt());
        assertTrue(engine.close(result.trade(), observation(quote(NOW.plusSeconds(3), "100", "101", "10")), NOW.plusSeconds(3)).events().isEmpty());
    }

    @Test void actualSignedFundingIsAppliedOnceWithCorrectLongShortDirection() {
        for (var side : DirectionalAction.values()) {
            var trade = open(side);
            var at = NOW.plusSeconds(30);
            var quote = quote(at, "100", "101", "10");
            var rates = List.of(new PaperMarketObservation.Funding(at.minusSeconds(1), bd("0.0001"), bd("100"), "HISTORY"));
            var first = engine.evaluate(trade, new PaperMarketObservation(quote, List.of(), rates, at), at);
            decimal(side == DirectionalAction.BUY ? "0.01" : "-0.01", first.trade().fundingUsdt());
            var next = at.plusSeconds(1);
            var repeated = engine.evaluate(first.trade(), new PaperMarketObservation(quote(next, "100", "101", "10"), List.of(), rates, next), next);
            assertTrue(repeated.events().isEmpty());
            assertEquals(first.trade().fundingUsdt(), repeated.trade().fundingUsdt());
        }
    }

    @Test void pendingOrderReplayedEntryAlsoReceivesHistoricalFunding() {
        var pending = pending(DirectionalAction.BUY, NOW.plusSeconds(600));
        var at = NOW.truncatedTo(ChronoUnit.MINUTES).plusSeconds(130);
        var funding = new PaperMarketObservation.Funding(at.minusSeconds(5), bd("-0.0001"), bd("100"), "HISTORY");
        var result = engine.evaluate(pending, new PaperMarketObservation(quote(at, "100", "101", "10"),
                List.of(bar(at.truncatedTo(ChronoUnit.MINUTES).minusSeconds(60), "100", "101", "99", "1000")), List.of(funding), at), at);
        decimal("-0.01", result.trade().fundingUsdt());
    }

    @Test void incompleteFundingBlocksExitAndDoesNotAdvanceCursor() {
        var trade = open(DirectionalAction.BUY);
        var at = NOW.plusSeconds(2);
        var market = new PaperMarketObservation(quote(at, "111", "112", "10"), List.of(), List.of(), trade.fundingCursor());
        var result = engine.evaluate(trade, market, at);
        assertEquals(PaperTrade.Health.FUNDING_DATA_PENDING, result.trade().healthCode());
        assertTrue(result.events().isEmpty());
        assertThrows(IllegalStateException.class, () -> engine.close(trade, market, at));
    }

    @Test void markPriceLiquidationIsExplicitlyAnEstimate() {
        var trade = open(DirectionalAction.BUY);
        var at = NOW.plusSeconds(2);
        var quote = new PaperMarketObservation.Quote("XAUUSDT", bd("100"), bd("101"), bd("10"), bd("10"), bd("75"), at, at, "PUBLIC");
        var result = evaluate(trade, quote);
        assertEquals(PaperTrade.Status.CLOSED, result.trade().status());
        assertTrue(result.events().getFirst().model().contains("LIQUIDATION_QUOTE_ESTIMATE"));
    }

    @Test void rejectsDuplicateOrFutureFundingEvidence() {
        var quote = quote(NOW.plusSeconds(2), "100", "101", "10");
        var funding = new PaperMarketObservation.Funding(NOW.plusSeconds(1), bd("0.0001"), bd("100"), "HISTORY");
        assertThrows(IllegalArgumentException.class, () -> new PaperMarketObservation(quote, List.of(), List.of(funding, funding), quote.occurredAt()));
        assertThrows(IllegalArgumentException.class, () -> new PaperMarketObservation(quote, List.of(),
                List.of(new PaperMarketObservation.Funding(NOW.plusSeconds(3), bd("0.1"), bd("100"), "HISTORY")), quote.occurredAt()));
    }

    private PaperMatchingResult replay(PaperTrade trade, String open, String high, String low, String volume) {
        var at = NOW.truncatedTo(ChronoUnit.MINUTES).plusSeconds(130);
        return engine.evaluate(trade, new PaperMarketObservation(quote(at, "100.5", "101", "10"),
                List.of(bar(at.truncatedTo(ChronoUnit.MINUTES).minusSeconds(60), open, high, low, volume)), List.of(), at), at);
    }
    private PaperTrade open(DirectionalAction side) {
        return evaluate(pending(side, NOW.plusSeconds(600)), quote(NOW.plusSeconds(1), side == DirectionalAction.BUY ? "99" : "100", side == DirectionalAction.BUY ? "100" : "101", "10")).trade();
    }
    private PaperMatchingResult evaluate(PaperTrade trade, PaperMarketObservation.Quote quote) { return engine.evaluate(trade, observation(quote), quote.observedAt()); }
    private static PaperMarketObservation observation(PaperMarketObservation.Quote quote) { return new PaperMarketObservation(quote, List.of(), List.of(), quote.occurredAt()); }
    private static PaperTrade pending(DirectionalAction side, Instant expires) {
        var terms = new PaperTradeTerms(side, bd("1"), bd("1"), bd("100"), bd(side == DirectionalAction.BUY ? "110" : "90"),
                bd(side == DirectionalAction.BUY ? "95" : "105"), bd("5"), bd("0.001"), bd("0.001"), bd("0.01"), "policy_test", expires);
        return new PaperTrade("paper_test001", "projection_test001", "instrument_xau_test", "XAUUSDT", terms, PaperTrade.Status.PENDING_ENTRY,
                NOW, null, null, null, null, NOW.truncatedTo(ChronoUnit.MINUTES), NOW, null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, PaperTrade.Health.READY, 0);
    }
    private static PaperMarketObservation.Quote quote(Instant at, String bid, String ask, String size) {
        return new PaperMarketObservation.Quote("XAUUSDT", bd(bid), bd(ask), bd(size), bd(size), bd("100"), at, at, "PUBLIC");
    }
    private static PaperMarketObservation.Bar bar(Instant at, String open, String high, String low, String volume) {
        return new PaperMarketObservation.Bar(at, bd(open), bd(high), bd(low), bd(open), bd(volume), bd("100"), bd("101"), bd("99"), "BARS");
    }
    private static BigDecimal bd(String value) { return new BigDecimal(value); }
    private static void decimal(String expected, BigDecimal actual) { assertEquals(0, bd(expected).compareTo(actual), actual.toPlainString()); }
}
