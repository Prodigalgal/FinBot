package io.omnnu.finbot.infrastructure.exchange;

import io.omnnu.finbot.application.exchange.ExchangeCapability;
import io.omnnu.finbot.application.exchange.ExchangeCapabilityQuery;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.MarketType;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class StaticExchangeCapabilityCatalog implements ExchangeCapabilityQuery {
    private static final Set<ExchangeEnvironment> LIVE_ONLY = Set.of(ExchangeEnvironment.LIVE);
    private static final Set<ExchangeEnvironment> GATE_MARKET_DATA = Set.of(
            ExchangeEnvironment.LIVE,
            ExchangeEnvironment.TESTNET);
    private static final Set<ExchangeEnvironment> BYBIT_MARKET_DATA = Set.of(
            ExchangeEnvironment.LIVE,
            ExchangeEnvironment.DEMO);
    private static final List<ExchangeCapability> CAPABILITIES = List.of(
            capability(ExchangeVenue.GATE, MarketType.SPOT, LIVE_ONLY, Set.of()),
            capability(
                    ExchangeVenue.GATE,
                    MarketType.LINEAR_PERPETUAL,
                    GATE_MARKET_DATA,
                    Set.of(ExchangeEnvironment.TESTNET)),
            capability(ExchangeVenue.GATE, MarketType.FUTURE, GATE_MARKET_DATA, Set.of()),
            capability(ExchangeVenue.BYBIT, MarketType.SPOT, BYBIT_MARKET_DATA, Set.of()),
            capability(
                    ExchangeVenue.BYBIT,
                    MarketType.LINEAR_PERPETUAL,
                    BYBIT_MARKET_DATA,
                    Set.of(ExchangeEnvironment.DEMO)),
            capability(ExchangeVenue.BYBIT, MarketType.INVERSE_PERPETUAL, BYBIT_MARKET_DATA, Set.of()),
            capability(ExchangeVenue.BYBIT, MarketType.FUTURE, BYBIT_MARKET_DATA, Set.of()));

    @Override
    public List<ExchangeCapability> list() {
        return CAPABILITIES;
    }

    private static ExchangeCapability capability(
            ExchangeVenue exchange,
            MarketType marketType,
            Set<ExchangeEnvironment> marketData,
            Set<ExchangeEnvironment> execution) {
        return new ExchangeCapability(exchange, marketType, marketData, execution);
    }
}
