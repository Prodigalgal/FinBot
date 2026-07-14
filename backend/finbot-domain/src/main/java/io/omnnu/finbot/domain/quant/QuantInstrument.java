package io.omnnu.finbot.domain.quant;

import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.shared.DomainText;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record QuantInstrument(
        QuantExchange exchange,
        InstrumentSymbol symbol,
        QuantMarketType marketType,
        String quoteCurrency) {
    private static final Pattern CURRENCY = Pattern.compile("[A-Z0-9]{2,12}");

    public QuantInstrument {
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(marketType, "marketType");
        quoteCurrency = DomainText.required(quoteCurrency, "quoteCurrency", 12).toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(quoteCurrency).matches()) {
            throw new IllegalArgumentException("quoteCurrency has an invalid format");
        }
    }
}
