package io.omnnu.finbot.application.trading;

import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.market.Price;
import io.omnnu.finbot.domain.trading.Confidence;
import io.omnnu.finbot.domain.trading.DecisionAction;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import java.util.List;
import java.util.Objects;

public record TradeDecisionDraft(
        DecisionAction action,
        InstrumentSymbol symbol,
        Confidence confidence,
        Price entryReference,
        Price targetPrice,
        Price invalidationPrice,
        List<String> rationale,
        List<String> evidenceReferences) {
    public TradeDecisionDraft {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(confidence, "confidence");
        rationale = List.copyOf(Objects.requireNonNull(rationale, "rationale"));
        evidenceReferences = List.copyOf(Objects.requireNonNull(
                evidenceReferences,
                "evidenceReferences"));
        if (action instanceof DirectionalAction) {
            Objects.requireNonNull(entryReference, "entryReference");
            Objects.requireNonNull(targetPrice, "targetPrice");
            Objects.requireNonNull(invalidationPrice, "invalidationPrice");
        } else if (entryReference != null || targetPrice != null || invalidationPrice != null) {
            throw new IllegalArgumentException("Non-directional decision must not contain prices");
        }
        if (rationale.isEmpty()) {
            throw new IllegalArgumentException("Trade decision requires rationale");
        }
    }
}
