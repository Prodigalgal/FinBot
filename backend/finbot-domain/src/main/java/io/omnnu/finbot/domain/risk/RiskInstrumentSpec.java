package io.omnnu.finbot.domain.risk;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.market.Price;
import io.omnnu.finbot.domain.shared.DecimalValue;
import java.math.BigDecimal;
import java.util.Objects;

public record RiskInstrumentSpec(
        InstrumentId instrumentId,
        ExchangeAccountId accountId,
        ExchangeVenue exchange,
        ExchangeEnvironment environment,
        InstrumentSymbol symbol,
        BigDecimal contractSize,
        BigDecimal quantityStep,
        BigDecimal minimumQuantity,
        BigDecimal venueMaximumLeverage,
        Price currentPrice,
        int openPositionCount) {

    public RiskInstrumentSpec {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(symbol, "symbol");
        contractSize = DecimalValue.positive(contractSize, "contractSize");
        quantityStep = DecimalValue.positive(quantityStep, "quantityStep");
        minimumQuantity = DecimalValue.positive(minimumQuantity, "minimumQuantity");
        venueMaximumLeverage = DecimalValue.positive(
                venueMaximumLeverage,
                "venueMaximumLeverage");
        Objects.requireNonNull(currentPrice, "currentPrice");
        if (openPositionCount < 0) {
            throw new IllegalArgumentException("openPositionCount must not be negative");
        }
    }
}
