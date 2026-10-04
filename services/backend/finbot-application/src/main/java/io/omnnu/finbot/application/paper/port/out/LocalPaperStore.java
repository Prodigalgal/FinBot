package io.omnnu.finbot.application.paper.port.out;

import io.omnnu.finbot.application.market.dto.ResearchMarketScope;
import io.omnnu.finbot.application.paper.dto.LocalPaperAccount;
import io.omnnu.finbot.application.paper.dto.LocalPaperCandidate;
import io.omnnu.finbot.application.paper.dto.LocalPaperTradePage;
import io.omnnu.finbot.domain.paper.PaperMatchingResult;
import io.omnnu.finbot.domain.paper.PaperTrade;
import io.omnnu.finbot.domain.paper.PaperTradeEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LocalPaperStore extends LocalPaperEligibility {
    boolean supports(ResearchMarketScope scope);
    LocalPaperAccount account();
    LocalPaperAccount setOrdersEnabled(boolean enabled, long expectedVersion, Instant now);
    LocalPaperTradePage trades(int limit, String beforeTradeId);
    Optional<PaperTrade> find(String tradeId);
    List<PaperTradeEvent> events(String tradeId, int limit);
    List<LocalPaperCandidate> candidates(Instant now, int limit);
    boolean reserve(LocalPaperCandidate candidate, PaperTrade trade, Instant now);
    List<PaperTrade> active(int limit);
    PaperTrade apply(long expectedVersion, PaperMatchingResult result, Instant now);
    void marketUnavailable(String tradeId, long expectedVersion, Instant now);
    void checkedAt(Instant now);
}
