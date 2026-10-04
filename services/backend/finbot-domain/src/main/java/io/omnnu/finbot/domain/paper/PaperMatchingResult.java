package io.omnnu.finbot.domain.paper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record PaperMatchingResult(PaperTrade trade, List<PaperTradeEvent> events) {
    public PaperMatchingResult {
        Objects.requireNonNull(trade, "trade");
        events = List.copyOf(events);
    }

    public BigDecimal cashDeltaUsdt() {
        return events.stream().map(PaperTradeEvent::cashDeltaUsdt).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
