package io.omnnu.finbot.application.paper.service;

import static org.junit.jupiter.api.Assertions.*;
import io.omnnu.finbot.application.market.exception.MarketDataFetchException;
import io.omnnu.finbot.application.paper.dto.LocalPaperCandidate;
import io.omnnu.finbot.application.paper.exception.LocalPaperConflictException;
import io.omnnu.finbot.application.paper.port.out.LocalPaperStore;
import io.omnnu.finbot.domain.paper.*;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LocalPaperServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:10Z");

    @Test void admitsAFreshScopedProjectionWithoutGeneratingAnImmediateFill() {
        var reserved = new AtomicReference<PaperTrade>();
        var store = store(List.of(), List.of(candidate()), reserved, new AtomicInteger());
        var service = new LocalPaperService(store, ignored -> observation(NOW, NOW), new PaperMatchingEngine(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(1, service.matchDue(50).toCompletableFuture().join());
        assertEquals(PaperTrade.Status.PENDING_ENTRY, reserved.get().status());
        assertEquals(NOW, reserved.get().createdAt());
        assertNull(reserved.get().enteredAt());
    }
    @Test void rejectsFutureAndStaleObservationTimesBeforeReservingAnyBalance() {
        for (var at : List.of(NOW.plusSeconds(1), NOW.minusSeconds(31))) {
            var reserved = new AtomicReference<PaperTrade>();
            var service = new LocalPaperService(store(List.of(), List.of(candidate()), reserved, new AtomicInteger()),
                    ignored -> observation(at, NOW), new PaperMatchingEngine(), Clock.fixed(NOW, ZoneOffset.UTC));
            assertEquals(0, service.matchDue(50).toCompletableFuture().join());
            assertNull(reserved.get());
        }
        var reserved = new AtomicReference<PaperTrade>();
        var service = new LocalPaperService(store(List.of(), List.of(candidate()), reserved, new AtomicInteger()),
                ignored -> observation(NOW, NOW.plusSeconds(1)), new PaperMatchingEngine(), Clock.fixed(NOW, ZoneOffset.UTC));
        service.matchDue(50).toCompletableFuture().join();
        assertNull(reserved.get());
    }
    @Test void marketFailurePreservesAnExistingOrderAndRecordsItsHealthWhileAdmissionIsPaused() {
        var unavailable = new AtomicInteger();
        var service = new LocalPaperService(store(List.of(pending()), List.of(), new AtomicReference<>(), unavailable),
                ignored -> { throw new MarketDataFetchException("PUBLIC_403", "unavailable"); },
                new PaperMatchingEngine(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(0, service.matchDue(50).toCompletableFuture().join());
        assertEquals(1, unavailable.get());
    }
    @Test void manualActionsRejectStaleVersionsBeforeAnyMarketRequest() {
        var service = new LocalPaperService(store(List.of(pending()), List.of(), new AtomicReference<>(), new AtomicInteger()),
                ignored -> { throw new AssertionError("must not read a quote"); }, new PaperMatchingEngine(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThrows(LocalPaperConflictException.class, () -> service.cancel("paper_test_candidate", 3));
    }
    private static LocalPaperStore store(List<PaperTrade> trades, List<LocalPaperCandidate> candidates,
                                        AtomicReference<PaperTrade> reserved, AtomicInteger unavailable) {
        return (LocalPaperStore) Proxy.newProxyInstance(LocalPaperStore.class.getClassLoader(), new Class<?>[]{LocalPaperStore.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "active" -> trades;
                    case "find" -> trades.stream().filter(trade -> trade.tradeId().equals(args[0])).findFirst();
                    case "candidates" -> candidates;
                    case "reserve" -> { reserved.set((PaperTrade) args[1]); yield true; }
                    case "marketUnavailable" -> { unavailable.incrementAndGet(); yield null; }
                    case "checkedAt" -> null;
                    default -> throw new AssertionError("Unexpected store call: " + method.getName());
                });
    }
    private static LocalPaperCandidate candidate() {
        return new LocalPaperCandidate("projection_test_candidate", "instrument_bybit_btcusdt", "BTCUSDT",
                new PaperTradeTerms(DirectionalAction.BUY, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("100"),
                        new BigDecimal("110"), new BigDecimal("95"), new BigDecimal("2"), new BigDecimal("0.001"),
                        BigDecimal.ZERO, new BigDecimal("0.005"), "policy_test", NOW.plusSeconds(600)), 3, NOW.minusSeconds(1));
    }
    private static PaperTrade pending() {
        return new PaperTrade("paper_test_candidate", candidate().projectionId(), candidate().instrumentId(), "BTCUSDT", candidate().terms(),
                PaperTrade.Status.PENDING_ENTRY, NOW, null, null, null, null, NOW, NOW, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, PaperTrade.Health.READY, 0);
    }
    private static PaperMarketObservation observation(Instant quoteAt, Instant observedAt) {
        return new PaperMarketObservation(new PaperMarketObservation.Quote("BTCUSDT", new BigDecimal("99"), new BigDecimal("100"),
                BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("100"), quoteAt, observedAt, "PUBLIC"), List.of(), List.of(), quoteAt);
    }
}
