package io.omnnu.finbot.application.market.port.out;

import io.omnnu.finbot.application.market.dto.MarketAnalysisScope;
import java.util.List;

@FunctionalInterface
public interface UserSelectedResearchScopeQuery {
    List<MarketAnalysisScope> scopes();
}
