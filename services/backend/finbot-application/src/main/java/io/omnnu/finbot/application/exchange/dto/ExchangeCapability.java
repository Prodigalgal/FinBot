package io.omnnu.finbot.application.exchange.dto;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.MarketType;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import java.util.Objects;
import java.util.Set;

public record ExchangeCapability(
        ExchangeVenue exchange,
        MarketType marketType,
        Set<ExchangeEnvironment> marketDataEnvironments,
        Set<ExchangeEnvironment> orderExecutionEnvironments) {
    public ExchangeCapability {
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(marketType, "marketType");
        marketDataEnvironments = Set.copyOf(marketDataEnvironments);
        orderExecutionEnvironments = Set.copyOf(orderExecutionEnvironments);
        if (orderExecutionEnvironments.contains(ExchangeEnvironment.LIVE)) {
            throw new IllegalArgumentException("FinBot does not expose live order execution");
        }
        if (!marketDataEnvironments.containsAll(orderExecutionEnvironments)) {
            throw new IllegalArgumentException("Execution environments require matching market data");
        }
    }

    public boolean supportsMarketData(ExchangeEnvironment environment) {
        return marketDataEnvironments.contains(environment);
    }

    public boolean supportsOrderExecution(ExchangeEnvironment environment) {
        return orderExecutionEnvironments.contains(environment);
    }
}
