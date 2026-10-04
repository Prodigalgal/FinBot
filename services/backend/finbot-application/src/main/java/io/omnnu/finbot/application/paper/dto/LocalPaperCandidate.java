package io.omnnu.finbot.application.paper.dto;

import io.omnnu.finbot.domain.paper.PaperTradeTerms;
import java.time.Instant;

public record LocalPaperCandidate(String projectionId, String instrumentId, String symbol,
                                  PaperTradeTerms terms, int maximumOpenPositions, Instant calculatedAt) {
}
