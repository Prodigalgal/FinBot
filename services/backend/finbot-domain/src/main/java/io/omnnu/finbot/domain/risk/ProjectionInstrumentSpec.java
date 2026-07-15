package io.omnnu.finbot.domain.risk;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.market.Price;
import io.omnnu.finbot.domain.shared.DecimalValue;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

public record ProjectionInstrumentSpec(
        InstrumentId instrumentId,
        ExchangeVenue exchange,
        InstrumentSymbol symbol,
        BigDecimal contractSize,
        BigDecimal quantityStep,
        BigDecimal minimumQuantity,
        BigDecimal venueMaximumLeverage,
        Optional<Price> currentPrice) {

    public ProjectionInstrumentSpec {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(symbol, "symbol");
        contractSize = DecimalValue.positive(contractSize, "contractSize");
        quantityStep = DecimalValue.positive(quantityStep, "quantityStep");
        minimumQuantity = DecimalValue.positive(minimumQuantity, "minimumQuantity");
        venueMaximumLeverage = DecimalValue.positive(
                venueMaximumLeverage,
                "venueMaximumLeverage");
        currentPrice = Objects.requireNonNull(currentPrice, "currentPrice");
    }
}
