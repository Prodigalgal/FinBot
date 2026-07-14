package io.omnnu.finbot.domain.market;

import io.omnnu.finbot.domain.shared.DecimalValue;
import io.omnnu.finbot.domain.shared.DomainText;
import java.math.BigDecimal;
import java.util.Locale;

public record Money(BigDecimal amount, String currency) {
    public Money {
        amount = DecimalValue.finite(amount, "amount");
        currency = DomainText.required(currency, "currency", 12).toUpperCase(Locale.ROOT);
    }
}
