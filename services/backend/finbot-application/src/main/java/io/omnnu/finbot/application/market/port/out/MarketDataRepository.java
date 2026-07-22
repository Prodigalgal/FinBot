package io.omnnu.finbot.application.market.port.out;

import io.omnnu.finbot.application.market.dto.MarketAnalysisScope;
import io.omnnu.finbot.application.market.dto.MarketCandle;
import io.omnnu.finbot.application.market.dto.MarketDataArtifactRecord;
import io.omnnu.finbot.application.market.dto.ResearchInstrument;

import io.omnnu.finbot.domain.research.ResearchArtifactId;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MarketDataRepository {
    List<ResearchInstrument> listResearchInstruments();

    Optional<ResearchInstrument> findInstrument(InstrumentId instrumentId);

    default Optional<ResearchInstrument> findResearchInstrument(
            ExchangeVenue exchange,
            String symbol) {
        return listResearchInstruments().stream()
                .filter(instrument -> instrument.exchange() == exchange)
                .filter(instrument -> instrument.symbol().equals(symbol))
                .findFirst();
    }

    void saveCandles(List<MarketCandle> candles);

    void saveResearchScope(
            WorkflowRunId workflowRunId,
            ResearchInstrument instrument,
            MarketAnalysisScope scope,
            java.math.BigDecimal marketReferencePrice,
            Instant capturedAt);

    void saveArtifact(MarketDataArtifactRecord artifact);

    Optional<MarketDataArtifactRecord> findArtifact(ResearchArtifactId artifactId);
}
