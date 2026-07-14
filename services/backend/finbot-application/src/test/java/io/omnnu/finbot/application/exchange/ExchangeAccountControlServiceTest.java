package io.omnnu.finbot.application.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.omnnu.finbot.application.configuration.ConfigurationConflictException;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExchangeAccountControlServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-14T09:00:00Z");
    private static final ExchangeAccountId ACCOUNT_ID =
            new ExchangeAccountId("account_bybit_demo_default");

    @Test
    void updatesEnabledStateWithOptimisticVersion() {
        var repository = new StubRepository(control(false, 4), false);
        var service = new ExchangeAccountControlService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var updated = service.setEnabled(ACCOUNT_ID, true, 4);

        assertEquals(true, updated.enabled());
        assertEquals(5, updated.version());
        assertEquals(NOW, updated.updatedAt());
    }

    @Test
    void rejectsConcurrentConfigurationChange() {
        var service = new ExchangeAccountControlService(
                new StubRepository(control(false, 4), true),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(
                ConfigurationConflictException.class,
                () -> service.setEnabled(ACCOUNT_ID, true, 3));
    }

    private static ExchangeAccountControl control(boolean enabled, long version) {
        return new ExchangeAccountControl(
                ACCOUNT_ID,
                ExchangeVenue.BYBIT,
                ExchangeEnvironment.DEMO,
                "Bybit Demo",
                enabled,
                version,
                NOW.minusSeconds(60));
    }

    private static final class StubRepository implements ExchangeAccountControlRepository {
        private final ExchangeAccountControl current;
        private final boolean conflict;

        private StubRepository(ExchangeAccountControl current, boolean conflict) {
            this.current = current;
            this.conflict = conflict;
        }

        @Override
        public Optional<ExchangeAccountControl> find(ExchangeAccountId accountId) {
            return Optional.of(current);
        }

        @Override
        public Optional<ExchangeAccountControl> setEnabled(
                ExchangeAccountId accountId,
                boolean enabled,
                long expectedVersion,
                Instant updatedAt) {
            if (conflict || current.version() != expectedVersion) {
                return Optional.empty();
            }
            return Optional.of(new ExchangeAccountControl(
                    current.accountId(),
                    current.exchange(),
                    current.environment(),
                    current.displayName(),
                    enabled,
                    current.version() + 1,
                    updatedAt));
        }
    }
}
