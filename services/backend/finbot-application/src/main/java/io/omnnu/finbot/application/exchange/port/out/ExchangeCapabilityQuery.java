package io.omnnu.finbot.application.exchange.port.out;

import io.omnnu.finbot.application.exchange.dto.ExchangeCapability;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.MarketType;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import java.util.List;
import java.util.Optional;

public interface ExchangeCapabilityQuery {
    List<ExchangeCapability> list();

    default Optional<ExchangeCapability> find(ExchangeVenue exchange, MarketType marketType) {
        return list().stream()
                .filter(capability -> capability.exchange() == exchange
                        && capability.marketType() == marketType)
                .findFirst();
    }

    default void requireMarketData(
            ExchangeVenue exchange,
            MarketType marketType,
            ExchangeEnvironment environment) {
        var supported = find(exchange, marketType)
                .map(capability -> capability.supportsMarketData(environment))
                .orElse(false);
        if (!supported) {
            throw new IllegalArgumentException(
                    exchange + " " + marketType + " does not support market data in " + environment);
        }
    }
}
