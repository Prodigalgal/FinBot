package io.omnnu.finbot.application.exchange;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.oms.OrderId;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import java.math.BigDecimal;
import java.time.Instant;

public record ExecutableOrder(
        OrderId orderId,
        int attemptNumber,
        ExchangeVenue exchange,
        ExchangeEnvironment environment,
        ExchangeAccountId accountId,
        InstrumentSymbol symbol,
        DirectionalAction side,
        BigDecimal quantity,
        BigDecimal leverage,
        String clientOrderId,
        Instant claimedUntil) {
}
