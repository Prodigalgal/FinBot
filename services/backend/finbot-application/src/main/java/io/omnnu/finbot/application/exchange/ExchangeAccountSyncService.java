package io.omnnu.finbot.application.exchange;

import io.omnnu.finbot.application.ledger.TradingLedgerWriter;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import java.time.Clock;
import java.time.Duration;
import io.omnnu.finbot.application.ledger.PositionSnapshotFact;
import io.omnnu.finbot.domain.ledger.LedgerFactId;
import io.omnnu.finbot.domain.ledger.PositionSide;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public final class ExchangeAccountSyncService implements ExchangeAccountSyncUseCase {
    private static final Duration INITIAL_HISTORY_WINDOW = Duration.ofDays(7);

    private final ExchangeAccountGateway gateway;
    private final ExchangeAccountConfigurationRepository accounts;
    private final ExchangeSyncCursorStore cursors;
    private final TradingLedgerWriter ledger;
    private final Clock clock;
    private final Executor executor;

    public ExchangeAccountSyncService(
            ExchangeAccountGateway gateway,
            ExchangeAccountConfigurationRepository accounts,
            ExchangeSyncCursorStore cursors,
            TradingLedgerWriter ledger,
            Clock clock,
            Executor executor) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletionStage<ExchangeAccountSyncResult> synchronize(ExchangeAccountId accountId) {
        return CompletableFuture.supplyAsync(() -> synchronizeNow(accountId), executor);
    }

    private ExchangeAccountSyncResult synchronizeNow(ExchangeAccountId accountId) {
        var account = accounts.find(accountId)
                .orElseThrow(() -> new ExchangeAccountNotFoundException("交易所账户不存在"));
        if (!account.enabled()) {
            return new ExchangeAccountSyncResult(
                    accountId,
                    0,
                    cursors.watermark(accountId).orElse(clock.instant()),
                    true,
                    java.util.List.of("Exchange account is disabled; synchronization skipped"));
        }
        var toExclusive = clock.instant().plusNanos(1);
        var fromInclusive = cursors.watermark(accountId)
                .orElse(toExclusive.minus(INITIAL_HISTORY_WINDOW));
        var batch = gateway.synchronize(accountId, fromInclusive, toExclusive);
        var count = ledger.appendAccountSnapshot(batch.accountSnapshot()) ? 1 : 0;
        count += batch.balances().stream().mapToInt(fact -> ledger.appendBalance(fact) ? 1 : 0).sum();
        count += batch.positions().stream().mapToInt(fact -> ledger.appendPosition(fact) ? 1 : 0).sum();
        var returnedSymbols = batch.positions().stream()
                .map(PositionSnapshotFact::symbol)
                .collect(java.util.stream.Collectors.toSet());
        for (var disappeared : cursors.currentOpenPositionSymbols(accountId)) {
            if (!returnedSymbols.contains(disappeared)) {
                var sourceId = "position-flat:" + disappeared.value() + ':' + toExclusive.toEpochMilli();
                var flat = new PositionSnapshotFact(
                        new LedgerFactId("position_" + hash(sourceId).substring(0, 40)),
                        accountId,
                        sourceId,
                        disappeared,
                        PositionSide.FLAT,
                        BigDecimal.ZERO,
                        null,
                        null,
                        null,
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        toExclusive.minusNanos(1),
                        toExclusive.minusNanos(1));
                count += ledger.appendPosition(flat) ? 1 : 0;
            }
        }
        count += batch.orders().stream().mapToInt(fact -> ledger.appendOrder(fact) ? 1 : 0).sum();
        count += batch.fills().stream().mapToInt(fact -> ledger.appendFill(fact) ? 1 : 0).sum();
        count += batch.realizedPnl().stream().mapToInt(fact -> ledger.appendRealizedPnl(fact) ? 1 : 0).sum();
        if (batch.complete()) {
            cursors.advance(accountId, batch.nextWatermark(), clock.instant());
        }
        return new ExchangeAccountSyncResult(
                accountId,
                count,
                batch.nextWatermark(),
                batch.complete(),
                batch.warnings());
    }

    private static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
