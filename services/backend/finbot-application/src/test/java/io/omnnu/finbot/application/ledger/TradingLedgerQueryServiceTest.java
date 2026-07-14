package io.omnnu.finbot.application.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.omnnu.finbot.application.configuration.EnvironmentValueResolver;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TradingLedgerQueryServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-14T08:00:00Z");

    @Test
    void reportsConfiguredFreshAndUnconfiguredAccountsWithoutInventingProfit() {
        var gate = projection(
                "account_gate_testnet_default",
                ExchangeVenue.GATE,
                "gate-key",
                "gate-secret",
                NOW.minusSeconds(30),
                "1000",
                "12",
                "5");
        var bybit = projection(
                "account_bybit_demo_default",
                ExchangeVenue.BYBIT,
                "bybit-key",
                "bybit-secret",
                null,
                "0",
                "0",
                "0");
        TradingLedgerQueryRepository repository = new StubLedgerRepository(List.of(gate, bybit));
        EnvironmentValueResolver environment = name -> name.startsWith("gate-")
                ? Optional.of("configured")
                : Optional.empty();
        var service = new TradingLedgerQueryService(
                repository,
                environment,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(10));

        var overview = service.accounts(new TradingTimeRange(NOW.minus(Duration.ofDays(30)), NOW.plusSeconds(1)));

        assertEquals(new BigDecimal("1000"), overview.totalEquity());
        assertEquals(new BigDecimal("12"), overview.totalUnrealizedPnl());
        assertEquals(new BigDecimal("5"), overview.totalRealizedPnl());
        assertEquals(AccountDataStatus.READY, overview.accounts().getFirst().dataStatus());
        assertEquals(AccountDataStatus.UNCONFIGURED, overview.accounts().getLast().dataStatus());
    }

    private static AccountLedgerProjection projection(
            String accountId,
            ExchangeVenue exchange,
            String keyEnv,
            String secretEnv,
            Instant snapshotAt,
            String equity,
            String unrealized,
            String realized) {
        return new AccountLedgerProjection(
                new ExchangeAccountId(accountId), exchange, ExchangeEnvironment.DEMO,
                exchange.name(), keyEnv, secretEnv, "exchange-ipv4", true, 0, "USDT",
                new BigDecimal(equity), new BigDecimal(equity), BigDecimal.ZERO,
                new BigDecimal(unrealized), new BigDecimal(realized), 0, snapshotAt);
    }

    private record StubLedgerRepository(List<AccountLedgerProjection> accounts)
            implements TradingLedgerQueryRepository {
        @Override
        public List<AccountLedgerProjection> accountOverview(TradingTimeRange range) {
            return accounts;
        }

        @Override
        public List<PositionView> currentPositions(ExchangeAccountId accountId) {
            return List.of();
        }

        @Override
        public TradingActivityPage activity(TradingActivityCriteria criteria) {
            return new TradingActivityPage(List.of(), null);
        }
    }
}
