package io.omnnu.finbot.application.paper.service;

import io.omnnu.finbot.application.market.dto.ResearchMarketScope;
import io.omnnu.finbot.application.market.exception.MarketDataFetchException;
import io.omnnu.finbot.application.operations.service.TaskCancellationContext;
import io.omnnu.finbot.application.paper.dto.LocalPaperAccount;
import io.omnnu.finbot.application.paper.dto.LocalPaperCandidate;
import io.omnnu.finbot.application.paper.dto.LocalPaperTradeDetail;
import io.omnnu.finbot.application.paper.dto.LocalPaperTradePage;
import io.omnnu.finbot.application.paper.exception.LocalPaperConflictException;
import io.omnnu.finbot.application.paper.exception.LocalPaperNotFoundException;
import io.omnnu.finbot.application.paper.port.in.LocalPaperUseCase;
import io.omnnu.finbot.application.paper.port.out.LocalPaperMarketGateway;
import io.omnnu.finbot.application.paper.port.out.LocalPaperStore;
import io.omnnu.finbot.domain.paper.PaperMarketObservation;
import io.omnnu.finbot.domain.paper.PaperMatchingEngine;
import io.omnnu.finbot.domain.paper.PaperMatchingResult;
import io.omnnu.finbot.domain.paper.PaperTrade;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class LocalPaperService implements LocalPaperUseCase {
    private static final System.Logger LOG = System.getLogger(LocalPaperService.class.getName());
    private static final BigDecimal MAXIMUM_ENTRY_DEVIATION = new BigDecimal("0.02");
    private final LocalPaperStore store;
    private final LocalPaperMarketGateway market;
    private final PaperMatchingEngine matching;
    private final Clock clock;

    public LocalPaperService(LocalPaperStore store, LocalPaperMarketGateway market, PaperMatchingEngine matching, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.market = Objects.requireNonNull(market, "market");
        this.matching = Objects.requireNonNull(matching, "matching");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public boolean supports(ResearchMarketScope scope) { return store.supports(scope); }
    @Override public LocalPaperAccount account() { return store.account(); }
    @Override public LocalPaperAccount setOrdersEnabled(boolean enabled, long expectedVersion) {
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
        return store.setOrdersEnabled(enabled, expectedVersion, clock.instant());
    }

    @Override public LocalPaperTradePage trades(int limit, String beforeTradeId) {
        if (beforeTradeId != null) validateId(beforeTradeId);
        return store.trades(limit, beforeTradeId);
    }

    @Override public java.util.List<PaperTrade> activeTrades() { return store.active(100); }

    @Override public LocalPaperTradeDetail detail(String tradeId) {
        return new LocalPaperTradeDetail(requiredTrade(tradeId), store.events(tradeId, 200));
    }

    @Override public PaperTrade cancel(String tradeId, long expectedVersion) {
        var trade = requiredTrade(tradeId);
        if (trade.status() == PaperTrade.Status.CANCELLED) return trade;
        requireVersion(trade, expectedVersion);
        PaperMatchingResult result;
        try { result = matching.cancel(trade, clock.instant()); }
        catch (IllegalStateException failure) { throw new LocalPaperConflictException("只能取消未成交的本地模拟挂单"); }
        return store.apply(expectedVersion, result, clock.instant());
    }

    @Override public PaperTrade close(String tradeId, long expectedVersion) {
        var trade = requiredTrade(tradeId);
        if (trade.status() == PaperTrade.Status.CLOSED) return trade;
        requireVersion(trade, expectedVersion);
        if (trade.status() != PaperTrade.Status.OPEN) throw new LocalPaperConflictException("只能平仓已成交的本地模拟持仓");
        var observation = market.observe(trade);
        PaperMatchingResult result;
        try {
            result = matching.close(trade, observation, clock.instant());
        } catch (IllegalStateException failure) {
            throw new LocalPaperConflictException("当前行情或资金费数据不完整，暂停本地模拟平仓");
        }
        return store.apply(expectedVersion, result, clock.instant());
    }

    @Override public CompletionStage<Integer> matchDue(int limit) {
        try { return CompletableFuture.completedFuture(match(Math.max(1, Math.min(100, limit)))); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }

    private int match(int limit) {
        var advanced = 0;
        // Existing positions retain their management even when new-order admission is disabled.
        for (var trade : store.active(limit)) {
            TaskCancellationContext.throwIfCancelled();
            PaperMarketObservation observation;
            try { observation = market.observe(trade); }
            catch (MarketDataFetchException failure) {
                TaskCancellationContext.throwIfCancelled();
                store.marketUnavailable(trade.tradeId(), trade.version(), clock.instant());
                LOG.log(System.Logger.Level.WARNING, "Paper market unavailable for {0}: {1}", trade.tradeId(), failure.errorCode());
                continue;
            }
            var result = matching.evaluate(trade, observation, clock.instant());
            TaskCancellationContext.throwIfCancelled();
            try {
                store.apply(trade.version(), result, clock.instant());
                advanced++;
            } catch (LocalPaperConflictException conflict) {
                LOG.log(System.Logger.Level.DEBUG, "Paper trade changed during market refresh: {0}", trade.tradeId());
            }
        }
        for (var candidate : store.candidates(clock.instant(), limit)) {
            TaskCancellationContext.throwIfCancelled();
            if (!candidate.terms().expiresAt().isAfter(clock.instant())) continue;
            var provisional = newTrade(candidate, clock.instant());
            PaperMarketObservation observation;
            try { observation = market.observe(provisional); }
            catch (MarketDataFetchException failure) {
                TaskCancellationContext.throwIfCancelled();
                LOG.log(System.Logger.Level.WARNING, "Paper admission market unavailable for {0}: {1}", candidate.projectionId(), failure.errorCode());
                continue;
            }
            var now = clock.instant();
            var quote = observation.quote();
            if (!candidate.symbol().equals(quote.symbol()) || quote.occurredAt().isAfter(now)
                    || quote.occurredAt().isBefore(now.minusSeconds(30)) || quote.observedAt().isAfter(now)
                    || quote.observedAt().isBefore(quote.occurredAt())) continue;
            var difference = candidate.terms().limitPrice().subtract(quote.markPrice()).abs();
            if (difference.compareTo(quote.markPrice().multiply(MAXIMUM_ENTRY_DEVIATION)) > 0) continue;
            if (!candidate.terms().expiresAt().isAfter(now)) continue;
            TaskCancellationContext.throwIfCancelled();
            if (store.reserve(candidate, newTrade(candidate, now), now)) advanced++;
        }
        store.checkedAt(clock.instant());
        return advanced;
    }

    private static PaperTrade newTrade(LocalPaperCandidate candidate, Instant now) {
        var tradeId = "paper_" + candidate.projectionId().substring("projection_".length());
        return new PaperTrade(tradeId, candidate.projectionId(), candidate.instrumentId(), candidate.symbol(), candidate.terms(),
                PaperTrade.Status.PENDING_ENTRY, now, null, null, null, null, now.truncatedTo(ChronoUnit.MINUTES), now,
                null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, PaperTrade.Health.READY, 0);
    }

    private PaperTrade requiredTrade(String tradeId) {
        validateId(tradeId);
        return store.find(tradeId).orElseThrow(() -> new LocalPaperNotFoundException("本地模拟交易不存在"));
    }

    private static void validateId(String tradeId) {
        if (tradeId == null || !tradeId.matches("^paper_[a-z0-9_-]{4,70}$")) throw new IllegalArgumentException("本地模拟交易 ID 无效");
    }

    private static void requireVersion(PaperTrade trade, long expectedVersion) {
        if (expectedVersion < 0 || trade.version() != expectedVersion) throw new LocalPaperConflictException("本地模拟交易已变化，请刷新后重试");
    }
}
