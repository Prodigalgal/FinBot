package io.omnnu.finbot.infrastructure.exchange.adapter;

import io.omnnu.finbot.infrastructure.exchange.adapter.StaticExchangeCapabilityCatalog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.MarketType;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import org.junit.jupiter.api.Test;

final class StaticExchangeCapabilityCatalogTest {
    private final StaticExchangeCapabilityCatalog catalog = new StaticExchangeCapabilityCatalog();

    @Test
    void exposesExactMarketAndExecutionEnvironments() {
        var gateLinear = catalog.find(ExchangeVenue.GATE, MarketType.LINEAR_PERPETUAL)
                .orElseThrow();
        assertTrue(gateLinear.supportsMarketData(ExchangeEnvironment.LIVE));
        assertTrue(gateLinear.supportsMarketData(ExchangeEnvironment.TESTNET));
        assertTrue(gateLinear.supportsOrderExecution(ExchangeEnvironment.TESTNET));
        assertFalse(gateLinear.supportsOrderExecution(ExchangeEnvironment.LIVE));

        var bybitLinear = catalog.find(ExchangeVenue.BYBIT, MarketType.LINEAR_PERPETUAL)
                .orElseThrow();
        assertTrue(bybitLinear.supportsMarketData(ExchangeEnvironment.DEMO));
        assertTrue(bybitLinear.supportsOrderExecution(ExchangeEnvironment.DEMO));
        assertFalse(bybitLinear.supportsMarketData(ExchangeEnvironment.TESTNET));
    }

    @Test
    void rejectsUnsupportedEnvironmentWithoutFallingBackToLive() {
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.requireMarketData(
                        ExchangeVenue.GATE,
                        MarketType.SPOT,
                        ExchangeEnvironment.TESTNET));
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.requireMarketData(
                        ExchangeVenue.BYBIT,
                        MarketType.LINEAR_PERPETUAL,
                        ExchangeEnvironment.TESTNET));
    }
}
