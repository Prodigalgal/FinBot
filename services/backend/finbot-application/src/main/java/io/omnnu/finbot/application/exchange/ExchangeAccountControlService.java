package io.omnnu.finbot.application.exchange;

import io.omnnu.finbot.application.configuration.ConfigurationConflictException;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import java.time.Clock;
import java.util.Objects;

public final class ExchangeAccountControlService implements ExchangeAccountControlUseCase {
    private final ExchangeAccountControlRepository repository;
    private final Clock clock;

    public ExchangeAccountControlService(ExchangeAccountControlRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ExchangeAccountControl setEnabled(
            ExchangeAccountId accountId,
            boolean enabled,
            long expectedVersion) {
        Objects.requireNonNull(accountId, "accountId");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        var current = repository.find(accountId)
                .orElseThrow(() -> new ExchangeAccountNotFoundException("交易所账户不存在"));
        if (current.enabled() == enabled) {
            return current;
        }
        return repository.setEnabled(accountId, enabled, expectedVersion, clock.instant())
                .orElseThrow(() -> new ConfigurationConflictException(
                        "交易所账户配置已被修改，请刷新后重试"));
    }
}
