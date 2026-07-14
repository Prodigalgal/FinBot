package io.omnnu.finbot.domain.trading;

import io.omnnu.finbot.domain.market.InstrumentSymbol;
import java.time.Instant;
import java.util.List;

public sealed interface TradeDecision permits DirectionalTradeDecision, NonDirectionalTradeDecision {
    TradeDecisionId id();

    InstrumentSymbol symbol();

    DecisionAction action();

    Confidence confidence();

    List<String> rationale();

    Instant createdAt();
}
