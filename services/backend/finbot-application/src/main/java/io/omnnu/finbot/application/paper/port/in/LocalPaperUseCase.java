package io.omnnu.finbot.application.paper.port.in;

import io.omnnu.finbot.application.market.dto.ResearchMarketScope;
import io.omnnu.finbot.application.paper.dto.LocalPaperAccount;
import io.omnnu.finbot.application.paper.dto.LocalPaperTradeDetail;
import io.omnnu.finbot.application.paper.dto.LocalPaperTradePage;
import io.omnnu.finbot.domain.paper.PaperTrade;
import java.util.concurrent.CompletionStage;

public interface LocalPaperUseCase {
    boolean supports(ResearchMarketScope scope);
    LocalPaperAccount account();
    LocalPaperAccount setOrdersEnabled(boolean enabled, long expectedVersion);
    LocalPaperTradePage trades(int limit, String beforeTradeId);
    java.util.List<PaperTrade> activeTrades();
    LocalPaperTradeDetail detail(String tradeId);
    PaperTrade cancel(String tradeId, long expectedVersion);
    PaperTrade close(String tradeId, long expectedVersion);
    CompletionStage<Integer> matchDue(int limit);
}
