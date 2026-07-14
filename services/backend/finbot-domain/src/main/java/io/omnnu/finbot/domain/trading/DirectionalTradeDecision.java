package io.omnnu.finbot.domain.trading;

import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.market.Price;
import io.omnnu.finbot.domain.shared.DomainText;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record DirectionalTradeDecision(
        TradeDecisionId id,
        InstrumentSymbol symbol,
        DirectionalAction action,
        Confidence confidence,
        Price entryReference,
        Price targetPrice,
        Price invalidationPrice,
        List<String> rationale,
        Instant createdAt) implements TradeDecision {

    public DirectionalTradeDecision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(entryReference, "entryReference");
        Objects.requireNonNull(targetPrice, "targetPrice");
        Objects.requireNonNull(invalidationPrice, "invalidationPrice");
        rationale = List.copyOf(Objects.requireNonNull(rationale, "rationale")).stream()
                .map(reason -> DomainText.required(reason, "rationale", 1_000))
                .toList();
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}
