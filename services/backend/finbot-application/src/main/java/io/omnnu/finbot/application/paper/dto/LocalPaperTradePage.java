package io.omnnu.finbot.application.paper.dto;

import io.omnnu.finbot.domain.paper.PaperTrade;
import java.util.List;

public record LocalPaperTradePage(List<PaperTrade> trades, String nextBeforeTradeId) {
    public LocalPaperTradePage { trades = List.copyOf(trades); }
}
