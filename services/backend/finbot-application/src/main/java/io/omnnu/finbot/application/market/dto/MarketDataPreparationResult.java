package io.omnnu.finbot.application.market.dto;

import io.omnnu.finbot.domain.quant.ResearchArtifact;
import io.omnnu.finbot.domain.research.ResearchArtifactId;
import io.omnnu.finbot.domain.research.ResearchDataPlane;
import java.util.List;

public record MarketDataPreparationResult(
        ResearchArtifactId artifactId,
        ResearchArtifact artifact,
        ResearchDataPlane dataPlane,
        List<MarketInstrumentBinding> instruments,
        List<MarketDataSourceResult> sources) {
    public MarketDataPreparationResult {
        instruments = List.copyOf(instruments);
        sources = List.copyOf(sources);
    }
}
