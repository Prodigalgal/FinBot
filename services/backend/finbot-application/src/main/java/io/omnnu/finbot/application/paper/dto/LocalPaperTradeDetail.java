package io.omnnu.finbot.application.paper.dto;

import io.omnnu.finbot.domain.paper.PaperTrade;
import io.omnnu.finbot.domain.paper.PaperTradeEvent;
import java.util.List;

public record LocalPaperTradeDetail(PaperTrade trade, List<PaperTradeEvent> events) {
    public LocalPaperTradeDetail { events = List.copyOf(events); }
}
