package io.omnnu.finbot.api.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.omnnu.finbot.application.configuration.RuntimeSecretSource;
import io.omnnu.finbot.application.ledger.AccountDataStatus;
import io.omnnu.finbot.application.ledger.AccountOverviewItem;
import io.omnnu.finbot.application.ledger.TradingAccountsOverview;
import io.omnnu.finbot.application.ledger.TradingTimeRange;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TradingResponsesTest {
    @Test
    void preservesExchangeCredentialMetadataInAccountResponse() {
        var now = Instant.parse("2026-07-16T13:00:00Z");
        var account = new AccountOverviewItem(
                new ExchangeAccountId("account_bybit_demo_default"),
                ExchangeVenue.BYBIT,
                ExchangeEnvironment.DEMO,
                "Bybit Demo",
                "exchange-ipv4",
                true,
                7,
                true,
                RuntimeSecretSource.ENVIRONMENT_FALLBACK,
                "keyfingerprint01",
                3,
                RuntimeSecretSource.DATABASE_OVERRIDE,
                "secretfingerpr01",
                5,
                AccountDataStatus.READY,
                "USDT",
                new BigDecimal("1000"),
                new BigDecimal("900"),
                new BigDecimal("100"),
                new BigDecimal("5"),
                new BigDecimal("12"),
                0,
                now);
        var overview = new TradingAccountsOverview(
                new TradingTimeRange(now.minusSeconds(3600), now.plusSeconds(1)),
                "USDT",
                account.equity(),
                account.availableBalance(),
                account.marginBalance(),
                account.unrealizedPnl(),
                account.realizedPnl(),
                List.of(account),
                now);

        var response = TradingResponses.AccountsOverviewResponse.from(overview).accounts().getFirst();

        assertEquals(RuntimeSecretSource.ENVIRONMENT_FALLBACK, response.apiKeySource());
        assertEquals("keyfingerprint01", response.apiKeyFingerprint());
        assertEquals(3, response.apiKeyVersion());
        assertEquals(RuntimeSecretSource.DATABASE_OVERRIDE, response.apiSecretSource());
        assertEquals("secretfingerpr01", response.apiSecretFingerprint());
        assertEquals(5, response.apiSecretVersion());
    }
}
