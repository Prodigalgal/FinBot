package io.omnnu.finbot.configuration.properties;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("finbot.trading-ledger")
public record TradingLedgerProperties(Duration staleAfter) {
    public TradingLedgerProperties {
        Objects.requireNonNull(staleAfter, "staleAfter");
        if (staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("finbot.trading-ledger.stale-after must be positive");
        }
    }
}
