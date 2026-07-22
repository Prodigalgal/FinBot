package io.omnnu.finbot.application.workflow.port.out;

import io.omnnu.finbot.domain.consensus.AnonymousCandidateId;
import io.omnnu.finbot.domain.consensus.SchulzeDetailedResult;
import io.omnnu.finbot.domain.research.ForecastSignal;
import java.util.List;

public interface SdbScaDocumentCodec {
    String encodeCandidateRanking(List<AnonymousCandidateId> candidates);

    String encodePairwiseMatrix(SchulzeDetailedResult result);

    String encodeStrongestPaths(SchulzeDetailedResult result);

    String encodeForecast(ForecastSignal forecast);
}
