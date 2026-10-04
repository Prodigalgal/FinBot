package io.omnnu.finbot.infrastructure.market.persistence;

import io.omnnu.finbot.application.market.dto.MarketAnalysisScope;
import io.omnnu.finbot.application.market.port.out.MarketDataRepository;
import io.omnnu.finbot.application.market.port.out.UserSelectedResearchScopeQuery;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;

@Repository
public final class StoredUserSelectedResearchScopeQuery implements UserSelectedResearchScopeQuery {
    private final MarketDataRepository markets;
    public StoredUserSelectedResearchScopeQuery(MarketDataRepository markets) { this.markets = Objects.requireNonNull(markets, "markets"); }
    @Override public List<MarketAnalysisScope> scopes() {
        var selected = markets.listResearchInstruments();
        if (selected.size() > 100) throw new IllegalStateException("默认自选研究商品超过 100 个，请缩小研究范围");
        return selected.stream().map(instrument -> new MarketAnalysisScope(instrument.instrumentId(), instrument.symbol(),
                instrument.exchange(), ExchangeEnvironment.LIVE, 3600, 86400)).toList();
    }
}
