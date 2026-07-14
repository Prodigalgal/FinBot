package io.omnnu.finbot.application.ledger;

import io.omnnu.finbot.application.configuration.EnvironmentValueResolver;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public final class TradingLedgerQueryService implements TradingLedgerQueryUseCase {
    private static final String REPORTING_CURRENCY = "USDT";

    private final TradingLedgerQueryRepository repository;
    private final EnvironmentValueResolver environment;
    private final Clock clock;
    private final Duration staleAfter;

    public TradingLedgerQueryService(
            TradingLedgerQueryRepository repository,
            EnvironmentValueResolver environment,
            Clock clock,
            Duration staleAfter) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.staleAfter = Objects.requireNonNull(staleAfter, "staleAfter");
    }

    @Override
    public TradingAccountsOverview accounts(TradingTimeRange range) {
        var now = clock.instant();
        var accounts = repository.accountOverview(range).stream()
                .map(projection -> toOverview(projection, now))
                .toList();
        return new TradingAccountsOverview(
                range,
                REPORTING_CURRENCY,
                sum(accounts, AccountOverviewItem::equity),
                sum(accounts, AccountOverviewItem::availableBalance),
                sum(accounts, AccountOverviewItem::marginBalance),
                sum(accounts, AccountOverviewItem::unrealizedPnl),
                sum(accounts, AccountOverviewItem::realizedPnl),
                accounts,
                now);
    }

    @Override
    public List<PositionView> currentPositions(ExchangeAccountId accountId) {
        return repository.currentPositions(accountId);
    }

    @Override
    public TradingActivityPage activity(TradingActivityCriteria criteria) {
        return repository.activity(criteria);
    }

    private AccountOverviewItem toOverview(AccountLedgerProjection projection, java.time.Instant now) {
        var credentialsConfigured = configured(projection.apiKeyEnv()) && configured(projection.apiSecretEnv());
        var dataStatus = status(projection, credentialsConfigured, now);
        return new AccountOverviewItem(
                projection.accountId(),
                projection.exchange(),
                projection.environment(),
                projection.displayName(),
                projection.proxyRoute(),
                projection.enabled(),
                projection.version(),
                credentialsConfigured,
                dataStatus,
                projection.currency(),
                projection.equity(),
                projection.availableBalance(),
                projection.marginBalance(),
                projection.unrealizedPnl(),
                projection.realizedPnl(),
                projection.openPositionCount(),
                projection.snapshotAt());
    }

    private AccountDataStatus status(
            AccountLedgerProjection projection,
            boolean credentialsConfigured,
            java.time.Instant now) {
        if (!projection.enabled() || !credentialsConfigured) {
            return AccountDataStatus.UNCONFIGURED;
        }
        if (projection.snapshotAt() == null) {
            return AccountDataStatus.NO_DATA;
        }
        if (projection.snapshotAt().plus(staleAfter).isBefore(now)) {
            return AccountDataStatus.STALE;
        }
        return AccountDataStatus.READY;
    }

    private boolean configured(String environmentVariable) {
        return environment.resolve(environmentVariable).filter(value -> !value.isBlank()).isPresent();
    }

    private static BigDecimal sum(
            List<AccountOverviewItem> accounts,
            java.util.function.Function<AccountOverviewItem, BigDecimal> extractor) {
        return accounts.stream()
                .filter(account -> REPORTING_CURRENCY.equals(account.currency()))
                .map(extractor)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
