package io.omnnu.finbot.application.market;

import io.omnnu.finbot.domain.research.ResearchArtifactId;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import java.util.List;
import java.util.Optional;

public interface MarketDataRepository {
    List<ResearchInstrument> listResearchInstruments();

    Optional<ResearchInstrument> findInstrument(InstrumentId instrumentId);

    void saveCandles(List<MarketCandle> candles);

    void saveArtifact(MarketDataArtifactRecord artifact);

    Optional<MarketDataArtifactRecord> findArtifact(ResearchArtifactId artifactId);
}
