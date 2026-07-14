package io.omnnu.finbot.domain.trading;

import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.shared.DomainText;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record NonDirectionalTradeDecision(
        TradeDecisionId id,
        InstrumentSymbol symbol,
        NonDirectionalAction action,
        Confidence confidence,
        List<String> rationale,
        Instant createdAt) implements TradeDecision {

    public NonDirectionalTradeDecision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(confidence, "confidence");
        rationale = List.copyOf(Objects.requireNonNull(rationale, "rationale")).stream()
                .map(reason -> DomainText.required(reason, "rationale", 1_000))
                .toList();
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}
