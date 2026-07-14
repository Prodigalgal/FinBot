package io.omnnu.finbot.application.market;

import io.omnnu.finbot.domain.quant.ResearchArtifact;
import io.omnnu.finbot.domain.research.ResearchArtifactId;
import java.util.List;

public record MarketDataPreparationResult(
        ResearchArtifactId artifactId,
        ResearchArtifact artifact,
        List<ResearchInstrument> instruments,
        List<MarketDataSourceResult> sources) {
    public MarketDataPreparationResult {
        instruments = List.copyOf(instruments);
        sources = List.copyOf(sources);
    }
}
